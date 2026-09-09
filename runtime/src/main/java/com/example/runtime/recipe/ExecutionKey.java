package com.example.runtime.recipe;

/** Ключ выполняющейся процедуры: одна сессия может вести несколько процедур разом. */
record ExecutionKey(String sessionId, String recipeId) {
}
