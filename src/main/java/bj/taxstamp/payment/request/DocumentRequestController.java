package bj.taxstamp.payment.request;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import bj.taxstamp.payment.common.CurrentUser;
import bj.taxstamp.payment.request.dto.CreateDocumentRequest;
import bj.taxstamp.payment.request.dto.DocumentRequestResponse;
import bj.taxstamp.payment.request.dto.DocumentTypeResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class DocumentRequestController {

    private final DocumentRequestService service;

    public DocumentRequestController(DocumentRequestService service) {
        this.service = service;
    }

    @GetMapping("/document-types")
    public List<DocumentTypeResponse> documentTypes() {
        return Arrays.stream(DocumentType.values()).map(DocumentTypeResponse::from).toList();
    }

    @PostMapping("/document-requests")
    public ResponseEntity<DocumentRequestResponse> create(CurrentUser user, @Valid @RequestBody CreateDocumentRequest payload) {
        DocumentRequest d = service.create(user.id(), payload.documentType(), payload.copies());
        return ResponseEntity.created(URI.create("/api/document-requests/" + d.getId())).body(toResponse(d));
    }

    @GetMapping("/document-requests")
    public List<DocumentRequestResponse> list(CurrentUser user) {
        return service.list(user.id()).stream().map(this::toResponse).toList();
    }

    @GetMapping("/document-requests/{id}")
    public DocumentRequestResponse get(CurrentUser user, @PathVariable UUID id) {
        return toResponse(service.get(user.id(), id));
    }

    private DocumentRequestResponse toResponse(DocumentRequest d) {
        return DocumentRequestResponse.from(d, service.status(d.getId()));
    }
}
