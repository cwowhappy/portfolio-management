package com.portfolio.invest.web;

import com.portfolio.invest.application.conversation.ChatMessageWire;
import com.portfolio.invest.application.conversation.ConversationApplicationService;
import com.portfolio.invest.application.conversation.ConversationView;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import com.portfolio.invest.web.dto.CreateConversationRequest;
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
import org.springframework.web.bind.annotation.RestController;

/** 会话接入层：所有操作以当前登录用户为归属，非本人 404。 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationApplicationService service;

    public ConversationController(ConversationApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<ConversationView> list(Authentication auth) {
        return service.list(currentUserId(auth));
    }

    @PostMapping
    public ResponseEntity<ConversationView> create(Authentication auth,
                                                   @Valid @RequestBody CreateConversationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(currentUserId(auth), req.id()));
    }

    @GetMapping("/{id}/messages")
    public List<ChatMessageWire> messages(Authentication auth, @PathVariable String id) {
        return service.messages(currentUserId(auth), id);
    }

    @PutMapping("/{id}/messages")
    public ResponseEntity<Void> saveMessages(Authentication auth, @PathVariable String id,
                                             @RequestBody List<ChatMessageWire> messages) {
        service.saveMessages(currentUserId(auth), id, messages);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication auth, @PathVariable String id) {
        service.delete(currentUserId(auth), id);
        return ResponseEntity.noContent().build();
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }
}
