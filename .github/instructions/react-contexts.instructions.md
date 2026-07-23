---
description: "Áp dụng khi làm việc với React Context provider (Theme, Settings, Confirm). Bao gồm pattern context, thứ tự bọc provider, và pattern confirm dialog bất đồng bộ."
applyTo: "app/src/context/**"
---

# Quy ước React Context

## Kiến trúc Context

Ba context trong dự án, mỗi cái có một trách nhiệm duy nhất:

| Context | Mục đích | Lưu trữ |
|---------|---------|---------|
| `ThemeContext` | State theme, theme tùy chỉnh, chế độ sáng/tối | `localStorage` |
| `SettingsContext` | 30+ cài đặt ứng dụng (proxy, ngôn ngữ, streaming, v.v.) | Tauri Store (`config.json`) |
| `ConfirmContext` | Confirm dialog bất đồng bộ qua Promise | Bộ nhớ |

## Thứ tự bọc Provider (trong `App.tsx`)

```tsx
<ThemeProvider>
  <SettingsProvider>
    <ConfirmProvider>
      <AppContent />
    </ConfirmProvider>
  </SettingsProvider>
</ThemeProvider>
```

## Mẫu Context Template

```tsx
interface MyContextValue {
  someState: string;
  setSomeState: (v: string) => void;
}

const MyContext = createContext<MyContextValue | undefined>(undefined);

export function MyProvider({ children }: { children: ReactNode }) {
  const [someState, setSomeState] = useState('default');
  return (
    <MyContext.Provider value={{ someState, setSomeState }}>
      {children}
    </MyContext.Provider>
  );
}

export function useMyContext(): MyContextValue {
  const ctx = useContext(MyContext);
  if (!ctx) throw new Error('useMyContext phải được dùng trong MyProvider');
  return ctx;
}
```

## ConfirmContext — Pattern Async Promise

```tsx
// Trong provider:
const [promise, setPromise] = useState<{ resolve: (v: boolean) => void } | null>(null);

const confirm = (message: string): Promise<boolean> => {
  return new Promise((resolve) => {
    setPromise({ resolve });
    setMessage(message);
  });
};

// Cách dùng trong component:
const confirmed = await confirm('Bạn có chắc không?');
if (confirmed) { /* thực hiện */ }
```

## SettingsContext

- Tải cài đặt từ Tauri Store khi mount.
- Cờ `isLoaded` ngăn render cho đến khi cài đặt sẵn sàng.
- `updateSetting(key, value)` tự động lưu vào store.
- Đối tượng settings là phẳng (không lồng nhau).
