// JNI-обёртка над llama.cpp для Local AI Chat (F003).
// Kotlin-сторона: com.chechestor.localaichat.llm.LlamaNative.
// Все вызовы одного движка идут из одного потока (см. LlmEngine), кроме requestStop.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <string>
#include <unistd.h>
#include <algorithm>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"

#define LOG_TAG "llm_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Коды результата generate(); те же константы — в LlamaNative.kt.
constexpr int RESULT_DONE = 0;          // модель закончила ответ (end of generation)
constexpr int RESULT_STOPPED = 1;       // остановлено по запросу
constexpr int RESULT_LENGTH = 2;        // достигнут лимит токенов ответа или конец контекста
constexpr int ERR_PROMPT_TOO_LONG = -2; // запрос не помещается в контекст
constexpr int ERR_DECODE = -3;          // ошибка llama_decode
constexpr int ERR_TOKENIZE = -4;
constexpr int ERR_BAD_HANDLE = -5;

struct Engine {
    FILE *file = nullptr;
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::vector<llama_token> cached; // токены, которые сейчас лежат в KV-кэше
    std::atomic<bool> stop{false};
};

void log_callback(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, "llama", "%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, "llama", "%s", text);
    } else if (level == GGML_LOG_LEVEL_INFO) {
        __android_log_print(ANDROID_LOG_INFO, "llama", "%s", text);
    }
}

bool abort_callback(void *data) {
    return static_cast<Engine *>(data)->stop.load();
}

std::string bytes_to_string(JNIEnv *env, jbyteArray bytes) {
    const jsize len = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(len), '\0');
    env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

bool tokenize(const Engine *e, const std::string &text, std::vector<llama_token> &out) {
    int32_t n = -llama_tokenize(e->vocab, text.data(), (int32_t) text.size(), nullptr, 0,
                                /*add_special*/ false, /*parse_special*/ true);
    if (n <= 0) {
        out.clear();
        return n == 0;
    }
    out.resize(n);
    int32_t got = llama_tokenize(e->vocab, text.data(), (int32_t) text.size(), out.data(), n,
                                 false, true);
    if (got < 0) return false;
    out.resize(got);
    return true;
}

// Длина начала буфера, состоящего только из целых UTF-8 символов.
size_t complete_utf8_prefix(const std::string &s) {
    size_t n = s.size();
    // Смотрим на последние до 3 байт: не начат ли там незаконченный символ.
    for (size_t back = 1; back <= 3 && back <= n; ++back) {
        auto c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue; // байт продолжения
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return back < need ? n - back : n;
    }
    return n;
}

jbyteArray to_jbytes(JNIEnv *env, const char *data, size_t len) {
    jbyteArray arr = env->NewByteArray((jsize) len);
    env->SetByteArrayRegion(arr, 0, (jsize) len, reinterpret_cast<const jbyte *>(data));
    return arr;
}

Engine *from_handle(jlong handle) { return reinterpret_cast<Engine *>(handle); }

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_chechestor_localaichat_llm_LlamaNative_backendInit(JNIEnv *env, jobject, jstring nativeLibDir) {
    llama_log_set(log_callback, nullptr);
    const char *dir = env->GetStringUTFChars(nativeLibDir, nullptr);
    // Загружает вариант CPU-бэкенда, подходящий процессору (libggml-cpu-*.so).
    ggml_backend_load_all_from_path(dir);
    env->ReleaseStringUTFChars(nativeLibDir, dir);
    llama_backend_init();
}

JNIEXPORT jstring JNICALL
Java_com_chechestor_localaichat_llm_LlamaNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

// Загрузка модели из открытого файлового дескриптора (файл или документ SAF).
// Дескриптор дублируется; вызывающий может закрыть свой после возврата.
JNIEXPORT jlong JNICALL
Java_com_chechestor_localaichat_llm_LlamaNative_loadModel(JNIEnv *, jobject, jint fd, jint nCtx,
                                                          jint nThreads, jboolean quantizeKv) {
    int dupFd = dup(fd);
    if (dupFd < 0) {
        LOGE("dup(fd) failed");
        return 0;
    }
    FILE *file = fdopen(dupFd, "rb");
    if (!file) {
        LOGE("fdopen failed");
        close(dupFd);
        return 0;
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP; // веса не копируются в ОЗУ, читаются из файла
    mparams.use_extra_bufts = false;          // без перепаковки весов: она копирует модель в ОЗУ

    llama_model *model = llama_model_load_from_file_ptr(file, mparams);
    if (!model) {
        fclose(file);
        LOGE("llama_model_load_from_file_ptr failed");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) nCtx;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;
    cparams.no_perf = true;
    if (quantizeKv) {
        // KV-кэш в q8_0 вдвое меньше f16; для квантованного V нужен Flash Attention.
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
        cparams.type_k = GGML_TYPE_Q8_0;
        cparams.type_v = GGML_TYPE_Q8_0;
    }

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("llama_init_from_model failed");
        llama_model_free(model);
        fclose(file);
        return 0;
    }

    auto *e = new Engine();
    e->file = file; // держим открытым, пока модель загружена
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    llama_set_abort_callback(ctx, abort_callback, e);
    LOGI("model loaded: n_ctx=%u threads=%d kv_q8=%d", llama_n_ctx(ctx), nThreads, (int) quantizeKv);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jint JNICALL
Java_com_chechestor_localaichat_llm_LlamaNative_contextSize(JNIEnv *, jobject, jlong handle) {
    Engine *e = from_handle(handle);
    return e ? (jint) llama_n_ctx(e->ctx) : 0;
}

JNIEXPORT jint JNICALL
Java_com_chechestor_localaichat_llm_LlamaNative_countTokens(JNIEnv *env, jobject, jlong handle,
                                                            jbyteArray utf8) {
    Engine *e = from_handle(handle);
    if (!e) return ERR_BAD_HANDLE;
    std::vector<llama_token> tokens;
    if (!tokenize(e, bytes_to_string(env, utf8), tokens)) return ERR_TOKENIZE;
    return (jint) tokens.size();
}

JNIEXPORT void JNICALL
Java_com_chechestor_localaichat_llm_LlamaNative_requestStop(JNIEnv *, jobject, jlong handle) {
    Engine *e = from_handle(handle);
    if (e) e->stop.store(true);
}

// Генерация ответа на готовый промпт (шаблон чата собирается в Kotlin).
// Части текста отдаются в callback.onBytes(byte[]) целыми UTF-8 символами.
JNIEXPORT jint JNICALL
Java_com_chechestor_localaichat_llm_LlamaNative_generate(JNIEnv *env, jobject, jlong handle,
                                                         jbyteArray promptUtf8, jint maxTokens,
                                                         jfloat temperature, jint topK, jfloat topP,
                                                         jfloat presencePenalty, jint seed,
                                                         jobject callback) {
    Engine *e = from_handle(handle);
    if (!e) return ERR_BAD_HANDLE;
    e->stop.store(false);

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onBytes = env->GetMethodID(cbClass, "onBytes", "([B)V");

    std::vector<llama_token> prompt;
    if (!tokenize(e, bytes_to_string(env, promptUtf8), prompt) || prompt.empty()) return ERR_TOKENIZE;

    const int nCtx = (int) llama_n_ctx(e->ctx);
    if ((int) prompt.size() >= nCtx) return ERR_PROMPT_TOO_LONG;

    // Переиспользуем общее начало с прошлым запросом: история диалога уже лежит в KV-кэше.
    size_t keep = 0;
    while (keep < e->cached.size() && keep < prompt.size() && e->cached[keep] == prompt[keep]) ++keep;
    if (keep == prompt.size()) --keep; // последний токен нужно прогнать заново, чтобы получить логиты
    llama_memory_t mem = llama_get_memory(e->ctx);
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) {
        llama_memory_clear(mem, true);
        keep = 0;
    }
    e->cached.resize(keep);

    // Обработка промпта пачками по n_batch.
    const int nBatch = (int) llama_n_batch(e->ctx);
    for (size_t i = keep; i < prompt.size(); i += nBatch) {
        int n = (int) std::min<size_t>(nBatch, prompt.size() - i);
        int rc = llama_decode(e->ctx, llama_batch_get_one(prompt.data() + i, n));
        if (rc == 2 || e->stop.load()) {
            // Прервано: в кэше остались только целиком обработанные пачки — не доверяем ему.
            llama_memory_clear(mem, true);
            e->cached.clear();
            return RESULT_STOPPED;
        }
        if (rc != 0) {
            LOGE("llama_decode(prompt) failed: %d", rc);
            llama_memory_clear(mem, true);
            e->cached.clear();
            return ERR_DECODE;
        }
        e->cached.insert(e->cached.end(), prompt.begin() + (long) i, prompt.begin() + (long) i + n);
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (topK > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    if (presencePenalty != 0.0f) {
        // Штраф за повтор уже сказанного в ответе (Qwen3.5, F009 п. 7); окно — весь контекст.
        // После top-k: на полном словаре штраф медленный (llama.h).
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
                llama_vocab_n_tokens(e->vocab), nCtx, 1.0f, 0.0f, presencePenalty));
    }
    if (topP > 0.0f && topP < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) seed));

    int result = RESULT_LENGTH;
    std::string pending;
    char piece[256];
    for (int produced = 0; produced < maxTokens; ++produced) {
        if (e->stop.load()) {
            result = RESULT_STOPPED;
            break;
        }
        if ((int) e->cached.size() >= nCtx) {
            result = RESULT_LENGTH;
            break;
        }
        llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) {
            result = RESULT_DONE;
            break;
        }
        int32_t len = llama_token_to_piece(e->vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) pending.append(piece, (size_t) len);

        size_t ready = complete_utf8_prefix(pending);
        if (ready > 0) {
            jbyteArray arr = to_jbytes(env, pending.data(), ready);
            env->CallVoidMethod(callback, onBytes, arr);
            env->DeleteLocalRef(arr);
            pending.erase(0, ready);
            if (env->ExceptionCheck()) {
                result = RESULT_STOPPED;
                break;
            }
        }

        int rc = llama_decode(e->ctx, llama_batch_get_one(&tok, 1));
        if (rc == 2) {
            result = RESULT_STOPPED;
            break;
        }
        if (rc != 0) {
            LOGE("llama_decode(token) failed: %d", rc);
            result = ERR_DECODE;
            break;
        }
        e->cached.push_back(tok);
    }
    if (!pending.empty() && !env->ExceptionCheck()) {
        jbyteArray arr = to_jbytes(env, pending.data(), pending.size());
        env->CallVoidMethod(callback, onBytes, arr);
        env->DeleteLocalRef(arr);
    }
    llama_sampler_free(smpl);
    e->stop.store(false);
    return result;
}

// Промпт по шаблону чата, встроенному в GGUF (модели, добавленные по названию, F010 п. 9).
// llama_chat_apply_template знает распространённые шаблоны (ChatML, Gemma, Llama 3, Phi…).
// null — в модели нет шаблона или он не распознан. Токен BOS дописывается текстом,
// если словарь его требует: шаблон его не добавляет, а промпт токенизируется без add_special.
JNIEXPORT jbyteArray JNICALL
Java_com_chechestor_localaichat_llm_LlamaNative_applyTemplate(JNIEnv *env, jobject, jlong handle,
                                                              jobjectArray roles,
                                                              jobjectArray contents) {
    Engine *e = from_handle(handle);
    if (!e) return nullptr;
    const char *tmpl = llama_model_chat_template(e->model, nullptr);
    if (!tmpl) return nullptr;

    const jsize n = env->GetArrayLength(roles);
    std::vector<std::string> roleStr((size_t) n), contentStr((size_t) n);
    std::vector<llama_chat_message> chat((size_t) n);
    size_t total = 0;
    for (jsize i = 0; i < n; ++i) {
        auto r = (jstring) env->GetObjectArrayElement(roles, i);
        const char *rc = env->GetStringUTFChars(r, nullptr);
        roleStr[(size_t) i] = rc;
        env->ReleaseStringUTFChars(r, rc);
        env->DeleteLocalRef(r);
        auto c = (jbyteArray) env->GetObjectArrayElement(contents, i);
        contentStr[(size_t) i] = bytes_to_string(env, c);
        env->DeleteLocalRef(c);
        total += contentStr[(size_t) i].size();
    }
    for (size_t i = 0; i < (size_t) n; ++i) chat[i] = {roleStr[i].c_str(), contentStr[i].c_str()};

    std::vector<char> buf(total * 2 + 1024);
    int32_t len = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(), (int32_t) buf.size());
    if (len < 0) {
        LOGW("chat template not supported");
        return nullptr;
    }
    if ((size_t) len > buf.size()) {
        buf.resize((size_t) len);
        len = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(), (int32_t) buf.size());
        if (len < 0) return nullptr;
    }
    std::string out(buf.data(), (size_t) len);
    if (llama_vocab_get_add_bos(e->vocab)) {
        const llama_token bos = llama_vocab_bos(e->vocab);
        const char *bosText = bos == LLAMA_TOKEN_NULL ? nullptr : llama_vocab_get_text(e->vocab, bos);
        if (bosText && out.rfind(bosText, 0) != 0) out.insert(0, bosText);
    }
    return to_jbytes(env, out.data(), out.size());
}

JNIEXPORT void JNICALL
Java_com_chechestor_localaichat_llm_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    Engine *e = from_handle(handle);
    if (!e) return;
    llama_free(e->ctx);
    llama_model_free(e->model);
    if (e->file) fclose(e->file);
    delete e;
}

} // extern "C"
