// 密码强度校验（前端提示用；后端仍会做最终校验）。

export const PASSWORD_MIN_LENGTH = 8;

export interface PasswordCheck {
  ok: boolean;
  /** 为空时无需提示；非空时是面向用户的中文错误信息。 */
  error?: string;
}

export function checkPassword(password: string): PasswordCheck {
  if (!password) return { ok: false, error: "请输入密码" };
  if (password.length < PASSWORD_MIN_LENGTH) {
    return { ok: false, error: `密码至少 ${PASSWORD_MIN_LENGTH} 位` };
  }
  if (!/[A-Za-z]/.test(password)) {
    return { ok: false, error: "密码需包含字母" };
  }
  if (!/\d/.test(password)) {
    return { ok: false, error: "密码需包含数字" };
  }
  return { ok: true };
}
