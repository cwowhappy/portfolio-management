import { z } from "zod";
import { request } from "./http";

export const SkillSchema = z.object({
  skillCode: z.string(), description: z.string(), category: z.string().nullable(),
  defaultEnabled: z.boolean(), dependsOnProvider: z.string().nullable(), enabled: z.boolean(),
});
export type SkillItem = z.infer<typeof SkillSchema>;

export const fetchSkills = () =>
  request<SkillItem[]>("/api/skills", "GET", undefined, z.array(SkillSchema));
export const saveSkillConfig = (enabled: string[]) =>
  request<SkillItem[]>("/api/skills/config", "PUT", { enabled }, z.array(SkillSchema));
