package com.example.editor.repository.recipe;

import com.example.editor.dto.recipe.RecipeResponseDto;
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
 * Хранилище процедурных рецептов на файлах: один файл — один рецепт, {@code <id>.json}
 * прямо в корне {@code storage-path}. {@code id} — слаг имени (плюс числовой суффикс
 * при коллизии), выделяется один раз при создании и не меняется при переименовании.
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
            String id = allocateId(recipe.getName());
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

    public List<RecipeResponseDto> findAll() {
        List<RecipeResponseDto> result = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(storageRoot, "*.json")) {
            for (Path file : files) {
                result.add(read(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list recipes in " + storageRoot, e);
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

    private String allocateId(String name) {
        String base = slugify(name);
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
