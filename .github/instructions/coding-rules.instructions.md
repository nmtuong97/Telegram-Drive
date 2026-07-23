---
applyTo: '**'
description: "Quy tắc suy nghĩ và coding: buộc agent suy nghĩ trước khi coding, giữ code đơn giản và thay đổi có chủ đích."
---

## 1. Suy Nghĩ Trước Khi Coding

**Không giả định. Không che giấu điều chưa rõ. Nêu rõ tradeoff.**

Trước khi implementation:
- Nêu rõ các giả định. Nếu không chắc, hãy hỏi.
- Nếu có nhiều cách diễn giải, trình bày chúng; không tự chọn mà không nói rõ.
- Nếu có cách đơn giản hơn, hãy nói rõ. Hãy phản biện khi cần.
- Nếu có điều chưa rõ, hãy dừng lại. Nêu cụ thể điểm gây nhầm lẫn và hỏi.

## 2. Ưu Tiên Đơn Giản

**Dùng lượng code tối thiểu để giải quyết vấn đề. Không suy đoán hoặc làm trước những gì chưa cần.**

- Không thêm feature ngoài yêu cầu.
- Không tạo abstraction cho code chỉ dùng một lần.
- Không thêm "flexibility" hoặc "configurability" chưa được yêu cầu.
- Không thêm error handling cho tình huống bất khả thi về mặt cấu trúc theo language hoặc framework, chẳng hạn input type-safe không thể là null. Hãy thêm error handling cho mọi input thực tế có thể xuất hiện khi runtime.
- Nếu solution có nhiều code hơn đáng kể so với yêu cầu, theo ước lượng thô là hơn gấp đôi số dòng cần thiết, hãy viết lại trước khi trả lời.

Hãy tự hỏi: "Một senior engineer có cho rằng cách này quá phức tạp không?" Nếu có, hãy đơn giản hóa.

## 3. Thay Đổi Có Chủ Đích

**Chỉ chạm vào phần bắt buộc phải thay đổi. Chỉ dọn phần rối do chính bạn tạo ra.**

Nếu không thể thực hiện đúng thay đổi được yêu cầu mà không sửa code lân cận, chẳng hạn phải cập nhật shared utility để tránh làm hỏng caller, hãy thực hiện thay đổi cần thiết, nêu rõ trong câu trả lời và giải thích lý do.

Khi sửa code hiện có:
- Không "cải thiện" code, comment hoặc format lân cận.
- Không refactor phần không bị hỏng.
- Tuân theo style hiện có, ngay cả khi bạn sẽ làm khác đi.
- Nếu thấy dead code không liên quan, hãy đề cập nhưng không xóa.

Khi thay đổi của bạn tạo ra phần mồ côi:
- Xóa import, variable hoặc function bị thay đổi của BẠN làm cho không còn được sử dụng.
- Không xóa dead code có sẵn nếu chưa được yêu cầu.
- Nếu không thể xác định chắc chắn dead code có sẵn hay do thay đổi của bạn tạo ra, hãy giữ nguyên và ghi chú trong câu trả lời thay vì xóa.

Tiêu chí kiểm tra: Mọi dòng thay đổi phải liên hệ trực tiếp với yêu cầu của người dùng.

## 4. Thực Thi Theo Mục Tiêu

**Xác định tiêu chí thành công. Lặp lại cho đến khi xác minh được.**

Chuyển task thành mục tiêu có thể xác minh:
- "Add validation" → "Xác định input không hợp lệ, xác nhận schema từ chối chúng, rồi implementation rule"
- "Fix the bug" → "Tái hiện vấn đề, áp dụng cách sửa tối thiểu, xác nhận vấn đề không còn xảy ra"
- "Refactor X" → "Bảo đảm type-check và lint pass trước và sau thay đổi"

Khi người dùng yêu cầu rõ ràng về test, tuân theo `standard-testing.instructions.md`.

Với task nhiều bước, nêu plan ngắn gọn:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Tiêu chí thành công rõ ràng cho phép bạn tự lặp lại quá trình xác minh. Tiêu chí mơ hồ ("làm cho chạy được") đòi hỏi phải liên tục hỏi lại.

---

**Các hướng dẫn này đang hiệu quả nếu:** diff có ít thay đổi không cần thiết hơn, ít phải viết lại do quá phức tạp hơn và câu hỏi làm rõ xuất hiện trước implementation thay vì sau khi mắc lỗi.
