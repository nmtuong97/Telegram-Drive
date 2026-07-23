---
description: "Áp dụng khi làm việc với mã frontend React/TypeScript trong app/src/. Bao gồm quy ước component, hooks, styling (Tailwind v4), i18n, quản lý state và Tauri IPC."
applyTo: "app/src/**"
---

# Quy ước Frontend React/TypeScript

## Quy ước Component

### Định nghĩa Component
- Sử dụng **named exports** với khai báo `function` (không dùng arrow function) cho component cấp cao nhất:
  ```tsx
  export function FileCard({ file, onSelect }: FileCardProps) { ... }
  ```
- **Default exports** chỉ dùng cho entry point (`App.tsx`) và module lazy-load (`MobileDashboard.tsx`).
- Props được typing bằng interface hoặc type ở đầu file.

### Thứ tự Hooks
Tuân thủ thứ tự nhất quán trong mỗi component:

1. `useTranslation()` / context hooks (`useTheme`, `useSettings`)
2. Gọi `useState()`
3. Gọi `useRef()`
4. Custom hooks (`useTelegramConnection`, `useFileOperations`)
5. `useQuery()` / `useMutation()` từ TanStack Query
6. Các khối `useEffect()` — mỗi khối chỉ đảm nhiệm một trách nhiệm duy nhất
7. Định nghĩa `useCallback()`
8. Gọi `useMemo()`

### Quy tắc useEffect
- Nhiều khối `useEffect` tập trung — không bao giờ gộp thành một effect lớn.
- Luôn trả về hàm cleanup: `return () => unlisten?.()`.
- Guard đầu hàm với điều kiện: `if (!isLoaded) return;`.
- Dùng hàm async bên trong (không đánh dấu callback của effect là `async`):
  ```tsx
  useEffect(() => {
    const fetchData = async () => { ... };
    fetchData();
  }, [dependency]);
  ```

### Cách dùng useRef
- DOM refs để truy cập trực tiếp phần tử.
- Pattern `latestRequestRef` để theo dõi race condition bất đồng bộ.
- Bộ hủy: `cancelledRef = useRef<Set<string>>(new Set())`.
- Callback refs cho tham chiếu hàm ổn định (giữ `.current` đồng bộ).

### useCallback
- Dùng cho event handler và thao tác phụ thuộc vào ref.
- Dùng pattern ref cho callback ổn định:
  ```tsx
  const selectedIdsRef = useRef(selectedIds);
  selectedIdsRef.current = selectedIds;
  ```

## Quản lý State

| Mối quan tâm | Cách tiếp cận |
|-------------|--------------|
| Dữ liệu server (file, thư mục, băng thông) | TanStack Query (`@tanstack/react-query`) |
| State toàn cục ứng dụng (theme, settings) | React Context (`ThemeContext`, `SettingsContext`, `ConfirmContext`) |
| State UI cục bộ (modal, tìm kiếm, chọn) | `useState` |
| Cấu hình bền vững | `@tauri-apps/plugin-store` (Tauri encrypted store) |
| Cài đặt phát trực tuyến | `localStorage` |

## Styling

- Chỉ dùng **Tailwind CSS v4** — không dùng CSS modules, không dùng styled-components.
- Dùng directive `@theme` trong `App.css` cho custom design tokens:
  ```css
  @import "tailwindcss";
  @theme {
    --color-telegram-bg: #0e1621;
    --color-telegram-primary: #ffae00;
  }
  ```
- Tham chiếu custom tokens: `bg-telegram-bg`, `text-telegram-primary`, `border-telegram-border`.
- Dynamic theming qua `themeEngine.ts` — chèn khối `<style>` ghi đè biến CSS.
- Chế độ sáng/tối qua class `.light` / `.dark` trên `<html>`.
- Framer Motion cho animation (`animate-in`, `fade-in`, `slide-in-from-bottom`).
- Không dùng thư viện `clsx` hoặc `classnames` — dùng template literal và toán tử ba ngôi.
- Glassmorphism qua class `.glass` và `.auth-glass` tùy chỉnh trong `App.css`.

## Xử lý lỗi

- **`try/catch`** bao quanh mọi lệnh `invoke()` gọi backend Rust.
- **`ErrorBoundary`** class component bọc app cấp cao nhất và dashboard.
- **Toast thông báo** với `sonner` cho lỗi hiển thị người dùng: `toast.error('Xóa thất bại: ${e}')`.
- **Pattern best-effort** với `.catch(() => {})` cho thao tác không quan trọng.
- **`console.warn`** cho lỗi có thể phục hồi, **`console.error`** trong ErrorBoundary.

## i18n (Đa ngôn ngữ)

- Dùng **`react-i18next`** với **import tĩnh** tất cả file JSON locale trong `i18n/index.ts`.
- Hook **`useTranslation()`**: `const { t } = useTranslation();`
- Cấu trúc khóa dịch: `'common.start'`, `'files.items_selected'`, `'settings.language'`.
- Thay ngôn ngữ qua `i18n.changeLanguage(settings.language)` trong `useEffect`.
- Hỗ trợ RTL: `document.documentElement.dir = 'rtl'` cho tiếng Ả Rập (`ar`).
- Tắt Suspense: `useSuspense: false` trong cấu hình i18n.
- Thêm file locale JSON mới vào `app/src/i18n/locales/` và đăng ký trong `languages.ts`.

## Import

- Chỉ dùng **đường dẫn tương đối** (không cấu hình alias đường dẫn).
- Ưu tiên **named imports** cho tất cả component, hook và tiện ích.
- Dynamic import với **template string hoàn toàn tĩnh** (yêu cầu của Vite):
  ```tsx
  React.lazy(() => import("./components/desktop/DesktopDashboard"))
  ```
- Plugin Tauri import từ `@tauri-apps/plugin-*`.
- Icon từ `lucide-react`.

## Quy tắc đặt tên file

| Loại | Quy ước | Ví dụ |
|------|---------|-------|
| Component | PascalCase | `DesktopDashboard.tsx`, `FileCard.tsx` |
| Hooks | camelCase với tiền tố `use` | `useFileOperations.ts`, `useTelegramConnection.ts` |
| Contexts | PascalCase | `SettingsContext.tsx`, `ThemeContext.tsx` |
| Tiện ích | camelCase | `utils.ts`, `themeEngine.ts`, `moovCache.ts` |
| Locale | kebab-case | `en.json`, `zh-CN.json`, `pt-BR.json` |

## Mã dành riêng cho nền tảng

- Tách mobile/desktop tại runtime qua hook `usePlatform()`.
- Cây component riêng biệt: `components/desktop/` và `components/mobile/`.
- Component dùng chung trong `components/shared/`.
- Code-splitting qua `React.lazy()` + `Suspense` cho mỗi dashboard nền tảng.

## Quy tắc TanStack Query

- Dùng `useQuery` cho đọc dữ liệu (file, thư mục, thống kê băng thông).
- Dùng `useMutation` cho ghi dữ liệu (upload, xóa, đổi tên).
- Invalidate queries sau mutation để làm mới dữ liệu.
- Giữ query key đơn giản và nhất quán.

## Tauri IPC (invoke)

- Gọi lệnh Rust bằng `invoke('cmd_xxx', { ... })` từ `@tauri-apps/api/core`.
- Luôn bọc trong `try/catch` để xử lý lỗi.
- Lắng nghe sự kiện Tauri qua `listen()` từ `@tauri-apps/api/event` với cleanup đúng trong `useEffect`.
