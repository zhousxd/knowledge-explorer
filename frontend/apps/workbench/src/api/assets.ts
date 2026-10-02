/**
 * 知识资源 API 客户端(FR-O01/O02 界面):
 * CSV 批量导入(multipart FormData)、列表(kind 筛选 + 标题检索 + offset 分页)、统一引用列表、卡片配图上传。
 */
import { http } from './http';
import type { AssetImportResult, AssetKind, AssetListResp, CitationItem, UploadImageResp } from './types';

export interface ListAssetsParams {
  /** 类型筛选;空串 = 全部 */
  kind?: AssetKind | '';
  /** 标题 ILIKE 关键词 */
  q?: string;
  /** 页码,从 1 起(el-pagination 语义) */
  page?: number;
  /** 每页条数,≤ 100 默认 20 */
  size?: number;
}

/** GET /api/wb/assets:id 升序;后端 offset 分页 1 基(三端点分页契约已统一 1 基:cards/reviews/assets) */
export async function listAssets(params: ListAssetsParams): Promise<AssetListResp> {
  const search = new URLSearchParams();
  if (params.kind) search.set('kind', params.kind);
  if (params.q) search.set('q', params.q);
  search.set('page', String(Math.max(params.page ?? 1, 1)));
  search.set('size', String(params.size ?? 20));
  return http.get<AssetListResp>(`/wb/assets?${search.toString()}`);
}

/** POST /api/wb/assets/import:CSV 批量导入(部分成功,errors 回报失败行号与原因) */
export function importAssets(file: File): Promise<AssetImportResult> {
  const form = new FormData();
  form.append('file', file);
  return http.post<AssetImportResult>('/wb/assets/import', form);
}

/** GET /api/wb/assets/{id}/citations:该资产的引用明细(id 升序) */
export function listCitations(assetId: number): Promise<CitationItem[]> {
  return http.get<CitationItem[]>(`/wb/assets/${assetId}/citations`);
}

/** POST /api/wb/images:卡片配图上传(multipart,≤5MB;后端按文件魔数判型,不看扩展名) */
export function uploadImage(file: File): Promise<UploadImageResp> {
  const form = new FormData();
  form.append('file', file);
  return http.post<UploadImageResp>('/wb/images', form);
}
