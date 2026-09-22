package com.example.channel.controller;

import com.example.channel.importer.CdbxImportReport;
import com.example.channel.importer.CdbxImportService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** Импорт базы каналов из .cdbx старой системы и выгрузка тегов для реестра шлюза. */
@RestController
@RequestMapping("/api/channel")
@RequiredArgsConstructor
public class ImportExportController {

    private final CdbxImportService importService;

    @Operation(summary = "Новая объектная база каналов из .cdbx; 409, если проект уже есть")
    @PostMapping(value = "/import/cdbx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CdbxImportReport importCdbx(@RequestParam("file") MultipartFile file,
                                       @RequestParam String site,
                                       @RequestParam String project) throws IOException {
        return importService.importFile(file.getBytes(), file.getOriginalFilename(), site, project);
    }

    @Operation(summary = "Удалить импортированный проект целиком; 409 для базы, созданной не импортом")
    @DeleteMapping("/import/{site}/{project}")
    public ResponseEntity<Void> deleteImported(@PathVariable String site, @PathVariable String project) {
        importService.deleteProject(site, project);
        return ResponseEntity.noContent().build();
    }
}
