package com.chechestor.localaichat.models

/** Имена репозиториев Hugging Face (`владелец/репозиторий`). */
object HfNames {
    private val REPO_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*$")

    fun isRepoId(text: String): Boolean = REPO_ID.matches(text) && !text.contains("..")
}
