package com.ke.service.asset;

import com.ke.service.asset.AssetImportService.AssetPage;
import com.ke.service.asset.AssetImportService.CitationItem;
import com.ke.service.asset.AssetImportService.CitationReq;
import com.ke.service.asset.AssetImportService.ImportResult;
import com.ke.service.common.ApiResponse;
import com.ke.service.common.BadRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 知识资产工作台端点（FR-O01/O02）。
 * 类级角色：CREATOR/EDITOR/OPERATOR（02 内容运营由编辑承担，CREATOR 可查看）；
 * 方法级收紧：CSV 导入仅 EDITOR/OPERATOR（越权由 GlobalExceptionHandler 转 403 envelope）。
 */
@RestController
@RequestMapping("/api/wb/assets")
@PreAuthorize("hasAnyRole('CREATOR','EDITOR','OPERATOR')")
public class AssetController {

    private final AssetImportService assets;

    public AssetController(AssetImportService assets) {
        this.assets = assets;
    }

    /** CSV 批量导入（UTF-8，表头行 kind,title,source_meta,locator,license,license_expire,content_extract） */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('EDITOR','OPERATOR')")
    public ApiResponse<ImportResult> importCsv(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("上传文件为空");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("文件读取失败");
        }
        return ApiResponse.ok(assets.importCsv(bytes));
    }

    /** 列表：1 基 offset 分页（page 缺省 1，与卡片/审核端点契约统一），size 缺省 20 ≤100 */
    @GetMapping
    public ApiResponse<AssetPage> list(@RequestParam(required = false) String kind,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(defaultValue = "1") Integer page,
                                       @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(assets.list(kind, q, page, size));
    }

    /** 为资产登记一条引用（objectType 白名单见 {@link AssetImportService}） */
    @PostMapping("/{id}/citations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String, Object>> addCitation(@PathVariable long id,
                                                        @RequestBody CitationReq req) {
        long citationId = assets.addCitation(id, req);
        return ApiResponse.ok(Map.of("citationId", citationId));
    }

    @GetMapping("/{id}/citations")
    public ApiResponse<List<CitationItem>> citations(@PathVariable long id) {
        return ApiResponse.ok(assets.listCitations(id));
    }
}
