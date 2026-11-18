package com.davidneto.homepage.cameras;

import com.davidneto.homepage.repository.WebDavUserRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Everything the API, web page and apps do with clips. */
@Service
public class ClipService {

    private final ClipFiles files;
    private final ClipThumbnailer thumbnailer;
    private final CameraClipRepository repo;
    private final WebDavUserRepository users;
    private final CameraProperties props;
    private final DiskSpace disk;
    private final Clock clock;

    public ClipService(ClipFiles files, ClipThumbnailer thumbnailer, CameraClipRepository repo,
                       WebDavUserRepository users, CameraProperties props, DiskSpace disk, Clock clock) {
        this.files = files;
        this.thumbnailer = thumbnailer;
        this.repo = repo;
        this.users = users;
        this.props = props;
        this.disk = disk;
        this.clock = clock;
    }

    public List<ClipView> list(ClipQuery q) {
        Specification<CameraClip> spec = (r, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (q.camera() != null && !q.camera().isBlank()) ps.add(cb.equal(r.get("cameraName"), q.camera()));
            if (q.trigger() != null && !q.trigger().isBlank()) {
                String t = q.trigger().toLowerCase();
                ps.add(cb.or(cb.equal(r.get("triggers"), t), cb.like(r.get("triggers"), t + ",%"),
                        cb.like(r.get("triggers"), "%," + t), cb.like(r.get("triggers"), "%," + t + ",%")));
            }
            if (q.from() != null) ps.add(cb.greaterThanOrEqualTo(r.get("startAt"), q.from().toInstant()));
            if (q.to() != null) ps.add(cb.lessThan(r.get("startAt"), q.to().toInstant()));
            if (q.kept() != null) ps.add(cb.equal(r.get("kept"), q.kept()));
            if (q.beforeId() != null) {
                Optional<CameraClip> cursor = repo.findById(q.beforeId());
                if (cursor.isPresent()) {
                    var start = cursor.get().getStartAt();
                    ps.add(cb.or(cb.lessThan(r.get("startAt"), start),
                            cb.and(cb.equal(r.get("startAt"), start), cb.lessThan(r.get("id"), q.beforeId()))));
                }
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        Sort sort = Sort.by(Sort.Order.desc("startAt"), Sort.Order.desc("id"));
        return repo.findAll(spec, PageRequest.of(0, q.effectiveLimit(), sort)).stream().map(ClipService::view).toList();
    }

    public Optional<ClipView> get(long id) {
        return repo.findById(id).map(ClipService::view);
    }

    public ClipView keep(long id, boolean kept) {
        CameraClip c = require(id);
        files.setKept(c.getStem(), kept); // the file on the volume is the truth; write it first
        c.setKept(kept);
        c.setKeptAt(kept ? clock.instant() : null);
        return view(repo.save(c));
    }

    public void delete(long id) {
        CameraClip c = require(id);
        if (files.isKept(c.getStem())) throw new ClipKeptException();
        files.deleteClip(c.getStem());
        thumbnailer.delete(c.getStem());
        repo.delete(c);
    }

    public Path videoPath(long id) {
        return files.mp4(require(id).getStem());
    }

    public Optional<Path> thumbPath(long id) {
        CameraClip c = require(id);
        if (c.getThumbPath() != null && Files.isRegularFile(Path.of(c.getThumbPath()))) {
            return Optional.of(Path.of(c.getThumbPath()));
        }
        return thumbnailer.ensure(c.getStem());
    }

    public List<String> cameras() {
        return repo.findCameraNames();
    }

    public StorageStatus status() {
        Path root = props.getRootDir();
        boolean volumeOk = files.volumeOk();
        long volumeTotal = volumeOk ? disk.total(root) : 0;
        long volumeFree = volumeOk ? disk.free(root) : 0;
        long quotaBytes = users.findByUsername(props.getWebdavUser())
                .map(u -> u.getStorageQuotaMb() * 1024L * 1024L).orElse(0L);
        long quotaUsed = volumeOk ? usedBytes(root) : 0;
        long rootTotal = disk.total(props.getRootDiskPath());
        long rootFree = disk.free(props.getRootDiskPath());

        Map<String, OffsetDateTime> newest = new TreeMap<>();
        for (CameraClip c : repo.findNewestPerCamera()) newest.put(c.getCameraName(), start(c));

        OffsetDateTime heartbeatAt = null;
        Boolean hubOk = null;
        var hb = files.readHeartbeat();
        if (hb.isPresent()) {
            try {
                heartbeatAt = OffsetDateTime.parse(hb.get().path("time").asString());
            } catch (RuntimeException ignored) {
                // malformed heartbeat: treated as missing
            }
            if (hb.get().has("hub_ok")) hubOk = hb.get().get("hub_ok").asBoolean();
        }

        List<String> warnings = new ArrayList<>();
        if (!volumeOk) warnings.add("camera volume missing");
        if (volumeOk && percentUsed(volumeTotal - volumeFree, volumeTotal) >= props.getWarnPercent()) {
            warnings.add("camera volume " + percentUsed(volumeTotal - volumeFree, volumeTotal) + "% full");
        }
        if (quotaBytes > 0 && percentUsed(quotaUsed, quotaBytes) >= props.getWarnPercent()) {
            warnings.add("camera quota " + percentUsed(quotaUsed, quotaBytes) + "% used");
        }
        if (percentUsed(rootTotal - rootFree, rootTotal) >= props.getRootDiskWarnPercent()) {
            warnings.add("VPS root disk " + percentUsed(rootTotal - rootFree, rootTotal) + "% full");
        }

        return new StorageStatus(volumeOk, volumeTotal, volumeFree, quotaBytes, quotaUsed,
                repo.sumSizeByKept(true), repo.sumSizeByKept(false), rootTotal, rootFree,
                newest, heartbeatAt, hubOk, List.copyOf(warnings));
    }

    private CameraClip require(long id) {
        return repo.findById(id).orElseThrow(() -> new ClipNotFoundException(id));
    }

    private static int percentUsed(long used, long total) {
        if (total <= 0) return 0;
        return (int) Math.min(100, Math.round(used * 100.0 / total));
    }

    private static long usedBytes(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static OffsetDateTime start(CameraClip c) {
        return c.getStartAt().atOffset(ZoneOffset.ofTotalSeconds(c.getUtcOffsetSeconds()));
    }

    static ClipView view(CameraClip c) {
        ZoneOffset off = ZoneOffset.ofTotalSeconds(c.getUtcOffsetSeconds());
        List<String> triggers = c.getTriggers().isEmpty() ? List.of() : Arrays.asList(c.getTriggers().split(","));
        return new ClipView(c.getId(), c.getCameraName(), c.getCameraId(), c.getStartAt().atOffset(off),
                c.getEndAt().atOffset(off), c.getDurationSeconds(), List.copyOf(triggers), c.getSizeBytes(),
                c.isKept());
    }
}
