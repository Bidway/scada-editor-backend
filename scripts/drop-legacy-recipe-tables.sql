-- Снос таблиц editor.recipe / editor.recipe_value / editor.recipe_change после перевода
-- рецептов на файловое хранилище (RecipeFileStore).
-- ddl-auto: update сам эти таблицы не удаляет.
--
-- ЗАПУСКАТЬ ВРУЧНУЮ И ТОЛЬКО ПОСЛЕ того, как весь код на этой ветке развёрнут и проверен.
-- Обратной миграции с файлов на БД этот скрипт не предполагает.

DROP TABLE IF EXISTS editor.recipe_change;
DROP TABLE IF EXISTS editor.recipe_value;
DROP TABLE IF EXISTS editor.recipe;
