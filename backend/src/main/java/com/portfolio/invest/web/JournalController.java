package com.portfolio.invest.web;

import com.portfolio.invest.application.journal.CreateJournalEntryCommand;
import com.portfolio.invest.application.journal.JournalApplicationService;
import com.portfolio.invest.application.journal.JournalEntryView;
import com.portfolio.invest.application.journal.TimelineEventView;
import com.portfolio.invest.application.journal.UpdateJournalEntryCommand;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/journal")
public class JournalController {

    private final JournalApplicationService service;

    public JournalController(JournalApplicationService service) {
        this.service = service;
    }

    @GetMapping("/entries")
    public List<JournalEntryView> entries(Authentication auth,
                                          @RequestParam(required = false) JournalEntryType type) {
        return service.entries(currentUserId(auth), type);
    }

    @PostMapping("/entries")
    public ResponseEntity<JournalEntryView> createEntry(Authentication auth,
                                                        @Valid @RequestBody CreateJournalEntryCommand cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createEntry(currentUserId(auth), cmd));
    }

    @GetMapping("/entries/{entryId}")
    public JournalEntryView getEntry(Authentication auth, @PathVariable Long entryId) {
        return service.getEntry(currentUserId(auth), entryId);
    }

    @PutMapping("/entries/{entryId}")
    public JournalEntryView updateEntry(Authentication auth, @PathVariable Long entryId,
                                        @Valid @RequestBody UpdateJournalEntryCommand cmd) {
        return service.updateEntry(currentUserId(auth), entryId, cmd);
    }

    @DeleteMapping("/entries/{entryId}")
    public ResponseEntity<Void> deleteEntry(Authentication auth, @PathVariable Long entryId) {
        service.deleteEntry(currentUserId(auth), entryId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/timeline")
    public List<TimelineEventView> timeline(Authentication auth,
                                            @RequestParam(required = false) LocalDate from,
                                            @RequestParam(required = false) LocalDate to) {
        return service.timeline(currentUserId(auth), from, to);
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }
}
