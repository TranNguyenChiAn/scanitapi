# ScanIt API

Repo backend. App mobile nằm ở repo `ScanIt` và chỉ gọi các API bên dưới.

Backend riêng cho cửa hàng quần áo (Spring Boot 3.4, Java 17, PostgreSQL, JDBC thuần, Flyway). Viết lại từ `jupiter_pos_be` theo mô hình quần áo: sản phẩm → biến thể (màu × size), giá lẻ/sỉ, tồn kho theo cửa hàng, phân quyền theo permission.

## Chạy

```bash
docker compose up -d                       # Postgres 16 (kèm pgvector)
export JWT_SECRET=$(openssl rand -base64 48)
export SEED_DEV_DATA=true                  # tài khoản + set 8922 mẫu
mvn spring-boot:run
```

Tài khoản mẫu: `admin / admin123`, `nhanvien / 123456`. Schema do Flyway tạo từ `src/main/resources/db/migration`.
Cấu hình qua biến môi trường, xem `.env.example`. Không có khoá hay mật khẩu nào trong mã nguồn.

## API (đã làm)

| Method | Đường dẫn | Quyền | Mô tả |
|---|---|---|---|
| POST | `/api/auth/login` | công khai | `{account,password}` → `{token,user{id,username,fullName,role,permissions[]}}` |
| GET | `/api/auth/me` | đăng nhập | thông tin + quyền hiện tại |
| GET | `/api/variants/by-barcode/{code}` | `scan.use` | quét mã: đúng 1 biến thể hoặc 404; khớp cả UPC-A/EAN-13 thêm số 0; ghi `scan_events` |
| GET | `/api/variants/{id}` | `product.view` | chi tiết biến thể |
| GET | `/api/variants?q=&page=&size=` | `product.view` | tìm theo tên, mã mẫu, SKU, mã vạch (size tối đa 50) |

Lỗi luôn có dạng `{code,message}`: 401 thiếu/hết hạn token, 403 thiếu quyền, 404 không thấy, 400 dữ liệu sai.
`costPrice` chỉ trả cho người có quyền `product.manage`.

## Khác BE cũ

- Tra mã vạch chính xác bằng index, không còn `LIKE '%x%'` rồi lọc phía client.
- Một lần gọi trả đủ: giá lẻ/sỉ, tồn kho, ảnh, màu/size còn lại của cùng sản phẩm.
- Quyền đọc lại từ DB mỗi request, khoá tài khoản có hiệu lực ngay; token chỉ chứa id.
- Thiếu token trả 401 (BE cũ trả 403 body rỗng).
- Schema do Flyway quản lý, không dùng `ddl-auto=update`. Không còn Lombok/MapStruct/JPA.
- Secrets đọc từ môi trường.

## Chưa làm (các bước tiếp theo)

Quản lý sản phẩm/giá/ảnh (CRUD + upload Cloudinary nén WebP, chống trùng sha256), nhập kho/kiểm kho, nhân viên và phân quyền, khách hàng, công nợ, đơn hàng, báo cáo, quét bằng ảnh (embedding + pgvector, xem `src/main/resources/db/optional`).

## Lưu ý kiểm thử

Mã Java **chưa được biên dịch hay chạy** trong môi trường viết ra nó (không tải được Maven). Các câu SQL đã chạy thử trên PostgreSQL 16. Lần đầu hãy chạy `mvn -q verify` rồi khởi động và thử bằng `curl` hoặc app.
