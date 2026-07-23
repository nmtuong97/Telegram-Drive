---
description: "Áp dụng khi làm việc với file i18n/locale, thêm ngôn ngữ mới, hoặc sửa đổi khóa dịch. Bao gồm đăng ký locale, xử lý RTL và quy ước đặt tên khóa."
applyTo: "app/src/i18n/**"
---

# Quy ước i18n & Bản địa hóa

## Kiến trúc

- **`react-i18next`** + **`i18next`** với import tĩnh tất cả file JSON locale.
- Tắt Suspense: `useSuspense: false` trong cấu hình i18n.
- Thay đổi ngôn ngữ được kích hoạt trong `App.tsx` qua `i18n.changeLanguage(settings.language)` trong `useEffect`.

## Thêm ngôn ngữ mới

1. Tạo file JSON mới trong `app/src/i18n/locales/<mã>.json` (ví dụ: `th.json`).
2. Import tĩnh trong `app/src/i18n/index.ts`:
   ```ts
   import th from '../locales/th.json';
   ```
3. Thêm vào đối tượng `resources` trong `i18n.init()`.
4. Thêm mã ngôn ngữ vào union type `SupportedLanguage` trong `app/src/i18n/languages.ts`.
5. Thêm mục `LanguageInfo` vào mảng `LANGUAGES` với:
   - `code` — mã ngôn ngữ (ví dụ: `'th'`)
   - `name` — tên bản địa (ví dụ: `'ไทย'`)
   - `flag` — emoji cờ (ví dụ: `'🇹🇭'`)
   - `dir` — `'ltr'` hoặc `'rtl'`

## Quy tắc đặt tên khóa dịch

Dùng dot-notation phẳng, tối đa 2 cấp lồng:

```
common.start
common.search_placeholder
files.items_selected
files.no_results
settings.language
settings.general.theme
```

## Hỗ trợ RTL

- Tiếng Ả Rập (`ar`) dùng hướng RTL: `document.documentElement.dir = 'rtl'`.
- Áp dụng tự động trong `App.tsx` dựa trên `settings.language`.
- Đảm bảo component UI xử lý RTL đúng (kiểm tra margin, padding, vị trí icon).

## Cách dùng trong Component

```tsx
import { useTranslation } from 'react-i18next';

function MyComponent() {
  const { t } = useTranslation();
  return <span>{t('common.start')}</span>;
}
```

## Cấu trúc file Locale

- JSON phẳng với khóa được tổ chức theo feature/domain.
- Tất cả 13 ngôn ngữ phải có khóa trùng khớp — khóa thiếu sẽ fallback về tiếng Anh.
- Hiện tại hỗ trợ: `en`, `vi`, `es`, `ru`, `zh-CN`, `fr`, `ar`, `pt-BR`, `de`, `hi`, `id`, `tr`, `ja`, `ko`.
