---
description: "Áp dụng khi làm việc với hệ thống theme, theme tùy chỉnh, theme preset, hoặc chèn biến CSS. Bao gồm các pattern theme engine và thêm preset mới."
applyTo: "app/src/theme/**"
---

# Quy ước Theme Engine

## Kiến trúc

- **`ThemeContext`** cung cấp state theme hiện tại và các hàm quản lý theme.
- **`themeEngine.ts`** xử lý DOM — chèn/xóa khối `<style>` ghi đè biến CSS.
- **`presets.ts`** định nghĩa các theme preset có sẵn.
- Theme tùy chỉnh được lưu trong `localStorage` key `'user-themes'`.
- Chế độ sáng/tối qua class `.dark` / `.light` trên `<html>`.

## Kiểu dữ liệu cốt lõi

```ts
interface ThemeColorPalette {
  bg: string;         // nền
  surface: string;    // nền card/bề mặt
  text: string;       // chữ chính
  subtext: string;    // chữ phụ
  border: string;     // màu viền
  hover: string;      // trạng thái hover
  primary: string;    // màu nhấn/chính
}

interface CustomTheme {
  id: string;
  name: string;
  isBuiltin: boolean;
  colors: ThemeColorPalette;
}
```

## Thêm Preset mới

1. Thêm đối tượng vào mảng `BUILTIN_THEMES` trong `presets.ts` với:
   - `id`: ID chuỗi duy nhất (ví dụ: `'cyber-teal'`)
   - `name`: tên hiển thị
   - `isBuiltin: true`
   - `colors`: đầy đủ `ThemeColorPalette`
2. Preset có sẵn không thể xóa (`isBuiltin: true`).

## Theme tùy chỉnh

- ID được tạo theo: `` `custom-${Date.now()}-${Math.random().toString(36).slice(2, 8)}` ``
- Truy cập localStorage an toàn: dùng wrapper `safeTryGet`/`safeTrySet`.
- `applyTheme(theme)` chèn khối `<style>` ghi đè biến CSS.
- `removeCustomTheme()` xóa khối style đã chèn.

## Pattern ghi đè biến CSS

```ts
function applyTheme(theme: CustomTheme) {
  const style = document.createElement('style');
  style.id = 'custom-theme';
  style.textContent = `
    :root {
      --color-telegram-bg: ${theme.colors.bg};
      --color-telegram-surface: ${theme.colors.surface};
      --color-telegram-text: ${theme.colors.text};
      --color-telegram-subtext: ${theme.colors.subtext};
      --color-telegram-border: ${theme.colors.border};
      --color-telegram-hover: ${theme.colors.hover};
      --color-telegram-primary: ${theme.colors.primary};
    }
  `;
  document.head.appendChild(style);
}
```
