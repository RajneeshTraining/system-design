package com.example.twitter.api;

import com.example.twitter.model.TimelinePage;
import com.example.twitter.service.TimelineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/{userId}")
class TimelineController {

    private final TimelineService timelines;

    TimelineController(TimelineService timelines) {
        this.timelines = timelines;
    }

    /** Home timeline (pushed tweets merged with pulled celebrity tweets). */
    @GetMapping("/timeline/home")
    TimelinePage home(@PathVariable long userId,
                      @RequestParam(required = false) Long maxId,
                      @RequestParam(required = false) Integer limit) {
        return timelines.home(userId, maxId, limit);
    }

    /** Profile timeline: the user's own tweets. */
    @GetMapping("/tweets")
    TimelinePage profile(@PathVariable long userId,
                         @RequestParam(required = false) Long maxId,
                         @RequestParam(required = false) Integer limit) {
        return timelines.profile(userId, maxId, limit);
    }
}
