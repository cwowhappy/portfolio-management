package com.portfolio.invest.web;

import com.portfolio.invest.application.wiki.CreatePrincipleRuleCommand;
import com.portfolio.invest.application.wiki.CreateWikiEntryCommand;
import com.portfolio.invest.application.wiki.PrincipleRuleApplicationService;
import com.portfolio.invest.application.wiki.PrincipleRuleView;
import com.portfolio.invest.application.wiki.UpdatePrincipleRuleCommand;
import com.portfolio.invest.application.wiki.UpdateWikiEntryCommand;
import com.portfolio.invest.application.wiki.WikiApplicationService;
import com.portfolio.invest.application.wiki.WikiEntryView;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import jakarta.validation.Valid;
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
@RequestMapping("/api/wiki")
public class WikiController {

    private final WikiApplicationService wikiService;
    private final PrincipleRuleApplicationService ruleService;

    public WikiController(WikiApplicationService wikiService, PrincipleRuleApplicationService ruleService) {
        this.wikiService = wikiService;
        this.ruleService = ruleService;
    }

    @GetMapping("/entries")
    public List<WikiEntryView> entries(Authentication auth,
                                       @RequestParam(required = false) WikiEntryType type) {
        return wikiService.entries(currentUserId(auth), type);
    }

    @PostMapping("/entries")
    public ResponseEntity<WikiEntryView> createEntry(Authentication auth,
                                                     @Valid @RequestBody CreateWikiEntryCommand cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(wikiService.createEntry(currentUserId(auth), cmd));
    }

    @GetMapping("/entries/{entryId}")
    public WikiEntryView getEntry(Authentication auth, @PathVariable Long entryId) {
        return wikiService.getEntry(currentUserId(auth), entryId);
    }

    @PutMapping("/entries/{entryId}")
    public WikiEntryView updateEntry(Authentication auth, @PathVariable Long entryId,
                                     @Valid @RequestBody UpdateWikiEntryCommand cmd) {
        return wikiService.updateEntry(currentUserId(auth), entryId, cmd);
    }

    @DeleteMapping("/entries/{entryId}")
    public ResponseEntity<Void> deleteEntry(Authentication auth, @PathVariable Long entryId) {
        wikiService.deleteEntry(currentUserId(auth), entryId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/rules")
    public List<PrincipleRuleView> rules(Authentication auth) {
        return ruleService.rules(currentUserId(auth));
    }

    @PostMapping("/rules")
    public ResponseEntity<PrincipleRuleView> createRule(Authentication auth,
                                                        @Valid @RequestBody CreatePrincipleRuleCommand cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ruleService.createRule(currentUserId(auth), cmd));
    }

    @PutMapping("/rules/{ruleId}")
    public PrincipleRuleView updateRule(Authentication auth, @PathVariable Long ruleId,
                                        @Valid @RequestBody UpdatePrincipleRuleCommand cmd) {
        return ruleService.updateRule(currentUserId(auth), ruleId, cmd);
    }

    @DeleteMapping("/rules/{ruleId}")
    public ResponseEntity<Void> deleteRule(Authentication auth, @PathVariable Long ruleId) {
        ruleService.deleteRule(currentUserId(auth), ruleId);
        return ResponseEntity.noContent().build();
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }
}
