import { z } from "zod";
import { request } from "./http";

export const McpProviderSchema = z.object({
  id: z.number(), code: z.string(), name: z.string(),
  authType: z.enum(["NONE", "BEARER", "HEADER"]),
  authHeader: z.string().nullable(), domains: z.array(z.string()),
});
export type McpProviderItem = z.infer<typeof McpProviderSchema>;

export const McpConfigSchema = z.object({
  providerId: z.number(), enabled: z.boolean(),
  disabledTools: z.array(z.string()), configVersion: z.number(),
});
export type McpConfigItem = z.infer<typeof McpConfigSchema>;

export const McpToolSchema = z.object({ name: z.string(), description: z.string(), enabled: z.boolean() });
export type McpToolItem = z.infer<typeof McpToolSchema>;

export const TestResultSchema = z.object({
  success: z.boolean(),
  tools: z.array(z.object({ name: z.string(), description: z.string() })),
  latencyMs: z.number(), errorMessage: z.string().nullable(),
});
export type TestResult = z.infer<typeof TestResultSchema>;

export const fetchProviders = () =>
  request<McpProviderItem[]>("/api/mcp/providers", "GET", undefined, z.array(McpProviderSchema));
export const fetchConfigs = () =>
  request<McpConfigItem[]>("/api/mcp/configs", "GET", undefined, z.array(McpConfigSchema));
export const saveConfig = (providerId: number, body: { enabled?: boolean; disabledTools?: string[] }) =>
  request<McpConfigItem>(`/api/mcp/configs/${providerId}`, "PUT", body, McpConfigSchema);
export const deleteConfig = (providerId: number) => request<void>(`/api/mcp/configs/${providerId}`, "DELETE");
export const testConnection = (providerId: number) =>
  request<TestResult>("/api/mcp/providers/test", "POST", { providerId }, TestResultSchema);
export const fetchTools = (providerId: number) =>
  request<McpToolItem[]>(`/api/mcp/configs/${providerId}/tools`, "GET", undefined, z.array(McpToolSchema));
