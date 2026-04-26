package com.davidneto.homepage.gallery.controller;

import com.davidneto.homepage.gallery.config.MaeProperties;
import com.davidneto.homepage.gallery.entity.GalleryItem;
import com.davidneto.homepage.gallery.repository.GalleryItemRepository;
import com.davidneto.homepage.gallery.service.GalleryItemService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.Month;
import java.util.*;

@Controller
@RequestMapping("/mae")
public class GalleryController {

    private final GalleryItemRepository repo;
    private final GalleryItemService items;
    private final MaeProperties props;

    public GalleryController(GalleryItemRepository repo, GalleryItemService items, MaeProperties props) {
        this.repo = repo;
        this.items = items;
        this.props = props;
    }

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("title", props.getTitle());
        return "mae/login";
    }

    @GetMapping({"", "/"})
    public String landing(Model model) {
        model.addAttribute("title", props.getTitle());
        model.addAttribute("recent",
                repo.findAllByOrderByUploadedAtDesc(PageRequest.of(0, 12)).getContent());

        List<Integer> years = repo.findDistinctYearsDesc();
        Map<Integer, List<MonthEntry>> byYear = new LinkedHashMap<>();
        for (Integer y : years) {
            List<MonthEntry> months = new ArrayList<>();
            for (var s : repo.findMonthSummaries(y)) {
                List<GalleryItem> preview =
                        repo.findByBucketYearAndBucketMonthOrderByTakenAtAscUploadedAtAsc(y, s.getMonth())
                            .stream().limit(6).toList();
                months.add(new MonthEntry(s.getMonth(), s.getItemCount(), preview));
            }
            byYear.put(y, months);
        }
        model.addAttribute("byYear", byYear);
        return "mae/landing";
    }

    @GetMapping("/recent")
    public String recent(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("title", props.getTitle());
        model.addAttribute("page", repo.findAllByOrderByUploadedAtDesc(PageRequest.of(page, 60)));
        return "mae/recent";
    }

    @GetMapping("/{year}/{month}")
    public String month(@PathVariable int year, @PathVariable int month, Model model) {
        model.addAttribute("title", props.getTitle());
        model.addAttribute("year", year);
        model.addAttribute("month", month);
        model.addAttribute("monthName", Month.of(month).name());
        model.addAttribute("items",
                repo.findByBucketYearAndBucketMonthOrderByTakenAtAscUploadedAtAsc(year, month));
        return "mae/month";
    }

    @GetMapping("/item/{id}")
    public String lightbox(@PathVariable long id, Model model) {
        GalleryItem item = items.find(id).orElseThrow();
        model.addAttribute("title", props.getTitle());
        model.addAttribute("item", item);

        List<GalleryItem> siblings = repo.findByBucketYearAndBucketMonthOrderByTakenAtAscUploadedAtAsc(
                item.getBucketYear(), item.getBucketMonth());
        int idx = -1;
        for (int i = 0; i < siblings.size(); i++) if (siblings.get(i).getId().equals(item.getId())) { idx = i; break; }
        model.addAttribute("prev", idx > 0 ? siblings.get(idx - 1) : null);
        model.addAttribute("next", idx >= 0 && idx < siblings.size() - 1 ? siblings.get(idx + 1) : null);
        return "mae/lightbox";
    }

    @GetMapping("/upload")
    public String upload(Model model) {
        model.addAttribute("title", props.getTitle());
        return "mae/upload";
    }

    public record MonthEntry(int month, long itemCount, List<GalleryItem> preview) {
        public String monthName() { return Month.of(month).name(); }
    }
}
