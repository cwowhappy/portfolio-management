import { describe, expect, it } from "vitest";
import { checkPassword, PASSWORD_MIN_LENGTH } from "@/lib/password";

describe("密码校验（lib/password）", () => {
  it("空密码返回错误", () => {
    expect(checkPassword("").ok).toBe(false);
  });

  it(`不足 ${PASSWORD_MIN_LENGTH} 位返回错误`, () => {
    const r = checkPassword("ab3c");
    expect(r.ok).toBe(false);
    expect(r.error).toContain(String(PASSWORD_MIN_LENGTH));
  });

  it("长度达标但缺少字母返回错误", () => {
    const r = checkPassword("12345678");
    expect(r.ok).toBe(false);
    expect(r.error).toContain("字母");
  });

  it("长度达标但缺少数字返回错误", () => {
    const r = checkPassword("abcdefgh");
    expect(r.ok).toBe(false);
    expect(r.error).toContain("数字");
  });

  it("≥8 位且含字母和数字时通过", () => {
    expect(checkPassword("passw0rd").ok).toBe(true);
    expect(checkPassword("Abcdef1!").ok).toBe(true);
  });
});
