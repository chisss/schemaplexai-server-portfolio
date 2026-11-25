package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.service.desktop.DesktopAttachmentService;
import com.schemaplexai.service.desktop.DesktopConversationService;
import com.schemaplexai.service.desktop.DesktopConversationService.Receipt;
import com.schemaplexai.service.desktop.DesktopConversationService.Send;
import com.schemaplexai.service.desktop.DesktopConversationService.Turn;
import com.schemaplexai.service.desktop.DesktopWorkbenchTemplate;
import com.schemaplexai.service.desktop.DesktopWorkspaceService;
import com.schemaplexai.service.desktop.DesktopWorkspaceService.Conversation;
import com.schemaplexai.service.desktop.DesktopWorkspaceService.CreateConversation;
import com.schemaplexai.service.desktop.DesktopWorkspaceService.CreateProject;
import com.schemaplexai.service.desktop.DesktopWorkspaceService.CreateWorkbench;
import com.schemaplexai.service.desktop.DesktopWorkspaceService.Project;
import com.schemaplexai.service.desktop.DesktopWorkspaceService.ProjectResource;
import com.schemaplexai.service.desktop.DesktopWorkspaceService.UpdateConversation;
import com.schemaplexai.service.desktop.DesktopWorkspaceService.UpdateProject;
import com.schemaplexai.service.desktop.DesktopWorkspaceService.UpdateWorkbench;
import com.schemaplexai.service.desktop.DesktopWorkspaceService.Workbench;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
public class DesktopWorkspaceController {
    private final DesktopWorkspaceService workspace;
    private final DesktopConversationService conversations;
    private final DesktopAttachmentService attachments;

    @GetMapping("/employee-projects")
    public R<PageResult<Project>> projects(@RequestParam(defaultValue = "1") @Min(1) int page,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return R.ok(workspace.projects(page, size));
    }

    @GetMapping("/employee-projects/{id}")
    public R<Project> project(@PathVariable String id) {
        return R.ok(workspace.project(id));
    }

    @PostMapping("/employee-projects")
    public R<Project> createProject(@Valid @RequestBody CreateProject request) {
        return R.ok(workspace.createProject(request));
    }

    @PatchMapping("/employee-projects/{id}")
    public R<Project> updateProject(@PathVariable String id, @Valid @RequestBody UpdateProject request) {
        return R.ok(workspace.updateProject(id, request));
    }

    public record AddResource(@NotBlank @Size(max = 64) String attachmentId) {}

    @GetMapping("/employee-projects/{id}/resources")
    public R<List<ProjectResource>> resources(@PathVariable String id) {
        return R.ok(workspace.resources(id));
    }

    @PostMapping("/employee-projects/{id}/resources")
    public R<Void> addResource(@PathVariable String id, @Valid @RequestBody AddResource request) {
        workspace.addResource(id, request.attachmentId());
        return R.ok();
    }

    @DeleteMapping("/employee-projects/{id}/resources/{attachmentId}")
    public R<Void> removeResource(@PathVariable String id, @PathVariable String attachmentId) {
        workspace.removeResource(id, attachmentId);
        return R.ok();
    }

    @GetMapping("/desktop/workbench-templates")
    public R<List<DesktopWorkbenchTemplate>> templates() {
        return R.ok(workspace.templates());
    }

    @GetMapping("/desktop/workbench-templates/{id}")
    public R<DesktopWorkbenchTemplate> template(@PathVariable String id) {
        return R.ok(workspace.templateDetails(id));
    }

    public record BindWorkbench(@NotBlank @Size(max = 64) String agentId) {}

    @PutMapping("/desktop/workbench-templates/{id}/binding")
    @PreAuthorize("@authorizationService.hasPermission('agent:manage')")
    public R<DesktopWorkbenchTemplate> bindWorkbench(@PathVariable String id,
                                                      @Valid @RequestBody BindWorkbench request) {
        return R.ok(workspace.bindTemplate(id, request.agentId()));
    }

    @GetMapping("/desktop/workbenches")
    public R<PageResult<Workbench>> workbenches(@RequestParam(defaultValue = "1") @Min(1) int page,
                                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return R.ok(workspace.workbenches(page, size));
    }

    @GetMapping("/desktop/workbenches/{id}")
    public R<Workbench> workbench(@PathVariable String id) {
        return R.ok(workspace.workbench(id));
    }

    @PostMapping("/desktop/workbenches")
    public R<Workbench> createWorkbench(@Valid @RequestBody CreateWorkbench request) {
        return R.ok(workspace.createWorkbench(request));
    }

    @PatchMapping("/desktop/workbenches/{id}")
    public R<Workbench> updateWorkbench(@PathVariable String id, @Valid @RequestBody UpdateWorkbench request) {
        return R.ok(workspace.updateWorkbench(id, request));
    }

    @GetMapping("/desktop/conversations")
    public R<PageResult<Conversation>> listConversations(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String workbenchId,
            @RequestParam(defaultValue = "false") boolean archived) {
        return R.ok(workspace.conversations(keyword, projectId, workbenchId, archived, page, size));
    }

    @GetMapping("/desktop/conversations/{id}")
    public R<Conversation> conversation(@PathVariable String id) {
        return R.ok(workspace.conversation(id));
    }

    @PostMapping("/desktop/conversations")
    public R<Conversation> createConversation(@Valid @RequestBody CreateConversation request) {
        return R.ok(workspace.createConversation(request));
    }

    @PatchMapping("/desktop/conversations/{id}")
    public R<Conversation> updateConversation(@PathVariable String id, @Valid @RequestBody UpdateConversation request) {
        return R.ok(workspace.updateConversation(id, request));
    }

    @GetMapping("/desktop/conversations/{id}/messages")
    public R<PageResult<Turn>> messages(@PathVariable String id,
                                        @RequestParam(defaultValue = "1") @Min(1) int page,
                                        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return R.ok(conversations.messages(id, page, size));
    }

    @PostMapping("/desktop/conversations/{id}/messages")
    @PreAuthorize("@authorizationService.hasPermission('agent:execute')")
    public R<Receipt> send(@PathVariable String id, @Valid @RequestBody Send request) {
        return R.ok(conversations.send(id, request));
    }

    @PostMapping(value = "/desktop/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authorizationService.hasPermission('agent:execute')")
    public R<DesktopAttachmentService.Attachment> upload(@RequestParam("file") MultipartFile file) throws IOException {
        try (var body = file.getInputStream()) {
            return R.ok(attachments.upload(file.getOriginalFilename(), body, file.getSize()));
        }
    }
}
