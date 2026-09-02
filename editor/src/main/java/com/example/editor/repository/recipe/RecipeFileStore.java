package com.example.editor.repository.recipe;

import com.example.editor.dto.recipe.RecipeResponseDto;
import com.example.editor.dto.recipe.RecipeValueDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Хранилище наборов значений (рецептов) на файлах вместо БД: один файл — один рецепт,
 * {@code <id>.json} прямо в корне {@code storage-path}. {@code id} — не счётчик, а
 * {@code <component_id>-<slug из имени>} (плюс числовой суффикс при коллизии): выделяется один
 * раз при создании и не меняется при переименовании рецепта — то же свойство, которым раньше
 * был Long PK.
 * <p>
 * Префикс {@code component_id} в id заодно позволяет {@link #findByComponentId} отбирать файлы
 * по имени, не читая содержимое каждого. Совпадение префикса ложным не бывает: числа-компоненты
 * разной длины ({@code 99} и {@code 991}) не совпадают на границе {@code "-"}.
 */
@Component
public class RecipeFileStore {

    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final Path storageRoot;
    private final ObjectMapper objectMapper;
    private final Object lock = new Object();

    public RecipeFileStore(@Value("${editor.recipes.storage-path:./data/recipes}") String storagePath,
                           ObjectMapper objectMapper) {
        this.storageRoot = Path.of(storagePath);
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create recipe storage directory: " + storageRoot, e);
        }
    }

    public RecipeResponseDto create(RecipeResponseDto recipe) {
        synchronized (lock) {
            String id = allocateId(recipe.getComponent_id(), recipe.getName());
            recipe.setId(id);
            write(id, recipe);
        }
        return recipe;
    }

    /** id не меняется, даже если поменялось имя. */
    public RecipeResponseDto update(RecipeResponseDto recipe) {
        write(recipe.getId(), recipe);
        return recipe;
    }

    public Optional<RecipeResponseDto> findById(String id) {
        Path file = pathFor(id);
        return Files.exists(file) ? Optional.of(read(file)) : Optional.empty();
    }

    public List<RecipeResponseDto> findByComponentId(Long componentId) {
        String prefix = componentId + "-";
        List<RecipeResponseDto> result = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(storageRoot, "*.json")) {
            for (Path file : files) {
                if (file.getFileName().toString().startsWith(prefix)) {
                    result.add(read(file));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list recipes for component " + componentId, e);
        }
        result.sort(Comparator.comparing(RecipeResponseDto::getId));
        return result;
    }

    public void deleteById(String id) {
        try {
            Files.deleteIfExists(pathFor(id));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete recipe " + id, e);
        }
    }

    /**
     * Переносит значения набора на новое имя свойства во всех рецептах компонента — файловый
     * эквивалент прежнего {@code RecipeValueRepository.renameRow}. Зовётся из
     * {@code ComponentPropertyServiceImpl.update} при точечном переименовании свойства.
     */
    public int renameProperty(Long componentId, String oldName, String newName) {
        synchronized (lock) {
            int moved = 0;
            for (RecipeResponseDto recipe : findByComponentId(componentId)) {
                boolean changed = false;
                for (RecipeValueDto value : recipe.getValues()) {
                    if (oldName.equals(value.getProperty_name())) {
                        value.setProperty_name(newName);
                        changed = true;
                        moved++;
                    }
                }
                if (changed) {
                    write(recipe.getId(), recipe);
                }
            }
            return moved;
        }
    }

    private String allocateId(Long componentId, String name) {
        String base = componentId + "-" + slugify(name);
        String candidate = base;
        int suffix = 2;
        while (Files.exists(pathFor(candidate))) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private static String slugify(String name) {
        String slug = NON_ALNUM.matcher(name.trim().toLowerCase(Locale.ROOT))
                .replaceAll("-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "recipe" : slug;
    }

    private Path pathFor(String id) {
        return storageRoot.resolve(id + ".json");
    }

    private void write(String id, RecipeResponseDto recipe) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(pathFor(id).toFile(), recipe);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write recipe file " + id, e);
        }
    }

    private RecipeResponseDto read(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), RecipeResponseDto.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read recipe file " + file, e);
        }
    }
}
