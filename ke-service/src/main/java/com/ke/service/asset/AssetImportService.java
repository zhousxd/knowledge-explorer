package com.ke.service.asset;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ke.infra.entity.CitationEntity;
import com.ke.infra.entity.KnowledgeAssetEntity;
import com.ke.infra.mapper.CitationMapper;
import com.ke.infra.mapper.KnowledgeAssetMapper;
import com.ke.service.common.BadRequestException;
import com.ke.service.common.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识资产导入与统一引用（FR-O01/O02）：
 * - CSV 批量导入：表头行必须包含 kind/title/locator 列（集合包含、顺序无关），否则整文件
 *   imported=0 并回报缺失列；数据行逐行校验（kind 枚举小写、title 必填、locator 必为 JSON 对象、
 *   source_meta 可空但给则须为 JSON 对象、license_expire 可空 yyyy-MM-dd），
 *   合法行入库；非法行与结构坏行（引号未闭合等，只作废该行）均跳过并按物理行号回报原因
 *   （部分成功语义，不做整文件回滚）；
 * - 列表查询：kind 精确过滤 + title ILIKE，分页（size ≤100 默认 20），
 *   expired = license_expire 非空且早于今日；citationCount 一条 GROUP BY 批量回填；
 * - citation：统一引用结构 = 资产 + 定位器 + 原文摘录（02 §4.2），
 *   objectType 白名单 {card_version, agent_run}（agent_run 为 Phase 5 预留，白名单先放行）。
 */
@Service
public class AssetImportService {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;
    static final int TITLE_MAX = 200;

    /** 表头行必须包含的列（集合包含而非全等，顺序无关；多余列如 source_meta 照常存在） */
    private static final List<String> REQUIRED_COLUMNS = List.of("kind", "title", "locator");

    private static final Set<String> KINDS = Set.of("book", "article", "audio", "video");
    private static final Set<String> CITATION_TYPES = Set.of("card_version", "agent_run");
    private static final DateTimeFormatter EXPIRE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private final KnowledgeAssetMapper assets;
    private final CitationMapper citations;
    private final ObjectMapper objectMapper;

    public AssetImportService(KnowledgeAssetMapper assets, CitationMapper citations, ObjectMapper objectMapper) {
        this.assets = assets;
        this.citations = citations;
        this.objectMapper = objectMapper;
    }

    // ---------- 导入（FR-O01） ----------

    public record ImportError(int line, String reason) {
    }

    public record ImportResult(int imported, int skipped, List<ImportError> errors) {
    }

    public ImportResult importCsv(byte[] bytes) {
        List<SimpleCsvParser.Row> rows = SimpleCsvParser.parse(bytes);
        if (rows.isEmpty()) {
            return new ImportResult(0, 0, List.of());
        }
        // 表头行校验（列名集合必须包含 kind/title/locator）：列结构错则整文件 imported=0，
        // 以表头行号回报缺失列，避免把数据行错当列位导出垃圾
        ImportError headerError = checkHeader(rows.get(0));
        if (headerError != null) {
            return new ImportResult(0, 1, List.of(headerError));
        }
        List<SimpleCsvParser.Row> dataRows = rows.subList(1, rows.size());
        int imported = 0;
        List<ImportError> errors = new ArrayList<>();
        for (SimpleCsvParser.Row row : dataRows) {
            // 结构坏行（引号未闭合等）只作废该行，回报行号后继续，绝不让整个请求 500
            if (row.failed()) {
                errors.add(new ImportError(row.line(), row.error()));
                continue;
            }
            String reason = validate(row.fields());
            if (reason != null) {
                errors.add(new ImportError(row.line(), reason));
                continue;
            }
            insertRow(row.fields());
            imported++;
        }
        return new ImportResult(imported, errors.size(), List.copyOf(errors));
    }

    /** 表头行合法返回 null；缺失必需列（或表头行本身是结构坏行）返回 ImportError */
    private ImportError checkHeader(SimpleCsvParser.Row header) {
        Set<String> columns = new HashSet<>();
        if (!header.failed()) {
            for (String field : header.fields()) {
                columns.add(field.trim().toLowerCase(Locale.ROOT));
            }
        }
        List<String> missing = REQUIRED_COLUMNS.stream().filter(c -> !columns.contains(c)).toList();
        if (missing.isEmpty()) {
            return null;
        }
        return new ImportError(header.line(), "表头缺少列: " + String.join(",", missing));
    }

    /** 行级校验，返回首个错误原因；合法返回 null */
    private String validate(List<String> f) {
        if (f.size() != 7) {
            return "列数应为 7，实际为 " + f.size();
        }
        String kind = f.get(0).trim().toLowerCase(Locale.ROOT);
        if (kind.isEmpty()) {
            return "kind 必填";
        }
        if (!KINDS.contains(kind)) {
            return "kind 非法: " + f.get(0).trim() + "（允许 book/article/audio/video）";
        }
        String title = f.get(1).trim();
        if (title.isEmpty()) {
            return "title 必填";
        }
        if (title.length() > TITLE_MAX) {
            return "title 超长（≤" + TITLE_MAX + " 字）";
        }
        if (f.get(3).trim().isEmpty()) {
            return "locator 必填";
        }
        if (parseJsonObject(f.get(3).trim()) == null) {
            return "locator 不是合法 JSON 对象: " + abbreviate(f.get(3).trim());
        }
        String sourceMeta = f.get(2).trim();
        if (!sourceMeta.isEmpty() && parseJsonObject(sourceMeta) == null) {
            return "source_meta 不是合法 JSON 对象: " + abbreviate(sourceMeta);
        }
        String expire = f.get(5).trim();
        if (!expire.isEmpty()) {
            try {
                LocalDate.parse(expire, EXPIRE_FORMAT);
            } catch (RuntimeException e) {
                return "license_expire 格式应为 yyyy-MM-dd: " + abbreviate(expire);
            }
        }
        return null;
    }

    private void insertRow(List<String> f) {
        KnowledgeAssetEntity asset = new KnowledgeAssetEntity();
        asset.setKind(f.get(0).trim().toLowerCase(Locale.ROOT));
        asset.setTitle(f.get(1).trim());
        String sourceMeta = f.get(2).trim();
        asset.setSourceMeta(sourceMeta.isEmpty() ? null : sourceMeta);
        asset.setLocator(f.get(3).trim());
        String license = f.get(4).trim();
        asset.setLicense(license.isEmpty() ? null : license);
        String expire = f.get(5).trim();
        asset.setLicenseExpire(expire.isEmpty() ? null : LocalDate.parse(expire, EXPIRE_FORMAT));
        String extract = f.get(6).trim();
        asset.setContentExtract(extract.isEmpty() ? null : extract);
        assets.insert(asset);
    }

    // ---------- 列表（FR-O01 人工核验） ----------

    public record AssetItem(Long id, String kind, String title, JsonNode sourceMeta, JsonNode locator,
                            String license, LocalDate licenseExpire, boolean expired, long citationCount) {
    }

    public record AssetPage(List<AssetItem> items, long total, int page, int size) {
    }

    @Transactional(readOnly = true)
    public AssetPage list(String kind, String q, Integer page, Integer size) {
        int safeSize = size == null ? DEFAULT_SIZE : Math.min(Math.max(size, 1), MAX_SIZE);
        int safePage = page == null ? 0 : Math.max(page, 0);
        long total = assets.selectCount(baseWhere(kind, q));
        QueryWrapper<KnowledgeAssetEntity> rowsWrapper = baseWhere(kind, q)
                .orderByAsc("id")
                .last("LIMIT " + safeSize + " OFFSET " + (long) safePage * safeSize);
        List<KnowledgeAssetEntity> rows = assets.selectList(rowsWrapper);

        // citationCount 批量回填（一条 GROUP BY，避免 N+1）
        Map<Long, Long> counts = new HashMap<>();
        if (!rows.isEmpty()) {
            List<Long> ids = rows.stream().map(KnowledgeAssetEntity::getId).toList();
            List<Map<String, Object>> grouped = citations.selectMaps(new QueryWrapper<CitationEntity>()
                    .select("asset_id", "COUNT(*) AS cnt")
                    .in("asset_id", ids)
                    .groupBy("asset_id"));
            for (Map<String, Object> row : grouped) {
                counts.put(((Number) row.get("asset_id")).longValue(),
                        ((Number) row.get("cnt")).longValue());
            }
        }
        LocalDate today = LocalDate.now();
        List<AssetItem> items = rows.stream().map(a -> new AssetItem(
                a.getId(), a.getKind(), a.getTitle(),
                jsonOrNull(a.getSourceMeta()), jsonOrNull(a.getLocator()),
                a.getLicense(), a.getLicenseExpire(),
                a.getLicenseExpire() != null && a.getLicenseExpire().isBefore(today),
                counts.getOrDefault(a.getId(), 0L))).toList();
        return new AssetPage(items, total, safePage, safeSize);
    }

    private QueryWrapper<KnowledgeAssetEntity> baseWhere(String kind, String q) {
        QueryWrapper<KnowledgeAssetEntity> wrapper = new QueryWrapper<>();
        if (kind != null && !kind.isBlank()) {
            wrapper.eq("kind", kind.trim().toLowerCase(Locale.ROOT));
        }
        if (q != null && !q.isBlank()) {
            wrapper.apply("title ILIKE {0}", "%" + q.trim() + "%");
        }
        return wrapper;
    }

    // ---------- 引用（FR-O02） ----------

    public record CitationReq(String objectType, Long objectId, String quote, String locator) {
    }

    public record CitationItem(Long id, String objectType, Long objectId, String quote, JsonNode locator) {
    }

    @Transactional
    public long addCitation(long assetId, CitationReq req) {
        requireAsset(assetId);
        if (req.objectType() == null || !CITATION_TYPES.contains(req.objectType())) {
            throw new BadRequestException("objectType 非法: " + req.objectType() + "（允许 card_version/agent_run）");
        }
        if (req.objectId() == null) {
            throw new BadRequestException("objectId 必填");
        }
        String locatorJson = null;
        if (req.locator() != null && !req.locator().isBlank()) {
            String trimmed = req.locator().trim();
            if (parseJsonObject(trimmed) == null) {
                throw new BadRequestException("locator 不是合法 JSON 对象");
            }
            locatorJson = trimmed;
        }
        CitationEntity citation = new CitationEntity();
        citation.setAssetId(assetId);
        citation.setObjectType(req.objectType());
        citation.setObjectId(req.objectId());
        citation.setQuote(req.quote() == null || req.quote().isBlank() ? null : req.quote().trim());
        citation.setLocator(locatorJson);
        citations.insert(citation);
        return citation.getId();
    }

    @Transactional(readOnly = true)
    public List<CitationItem> listCitations(long assetId) {
        requireAsset(assetId);
        return citations.selectList(new QueryWrapper<CitationEntity>()
                        .eq("asset_id", assetId).orderByAsc("id")).stream()
                .map(c -> new CitationItem(c.getId(), c.getObjectType(), c.getObjectId(),
                        c.getQuote(), jsonOrNull(c.getLocator())))
                .toList();
    }

    // ---------- 内部 ----------

    private void requireAsset(long assetId) {
        if (assets.selectById(assetId) == null) {
            throw new NotFoundException("知识资产不存在");
        }
    }

    /** 解析为 JSON 对象节点；非法或非 object 返回 null */
    private JsonNode parseJsonObject(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private JsonNode jsonOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("资产 JSON 数据损坏");
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 50 ? s : s.substring(0, 50) + "…";
    }
}
