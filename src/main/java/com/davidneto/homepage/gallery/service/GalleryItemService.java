package com.davidneto.homepage.gallery.service;

import com.davidneto.homepage.gallery.entity.GalleryItem;
import com.davidneto.homepage.gallery.repository.GalleryItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class GalleryItemService {

    private final GalleryItemRepository repo;
    private final GalleryStorage storage;

    public GalleryItemService(GalleryItemRepository repo, GalleryStorage storage) {
        this.repo = repo;
        this.storage = storage;
    }

    public Optional<GalleryItem> find(long id) { return repo.findById(id); }

    @Transactional
    public GalleryItem updateCaption(long id, String caption, String editorName) {
        GalleryItem item = repo.findById(id).orElseThrow(NoSuchElementException::new);
        item.setCaption(caption);
        item.setCaptionUpdatedAt(LocalDateTime.now());
        item.setCaptionUpdatedBy(editorName == null || editorName.isBlank() ? null : editorName);
        return item;
    }

    @Transactional
    public void delete(long id) throws IOException {
        GalleryItem item = repo.findById(id).orElseThrow(NoSuchElementException::new);
        String ext = MediaTypeSniffer.extensionFor(item.getContentType());
        repo.delete(item);
        storage.deleteAll(item.getStorageKey(), ext);
    }
}
