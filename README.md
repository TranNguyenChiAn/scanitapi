# ScanIt API

Repo backend. App mobile nằm ở repo `ScanIt` và chỉ gọi các API bên dưới.

Backend riêng cho cửa hàng quần áo (Spring Boot 3.4, Java 17, PostgreSQL, JDBC thuần, Liquibase). Viết lại từ `jupiter_pos_be` theo mô hình quần áo: sản phẩm → biến thể (màu × size), giá lẻ/sỉ, tồn kho theo cửa hàng, phân quyền theo permission.

## Chạy

```bash
docker compose up -d db                    # Postgres 16 kèm pgvector
docker compose exec db psql -U scanit -c "CREATE EXTENSION IF NOT EXISTS vector;"   # 1 lần
mvn spring-boot:run                        # profile dev: tự có khoá JWT thử và dữ liệu mẫu
```

Dùng Postgres cài sẵn (Homebrew) thay vì Docker: `brew install pgvector`, rồi bằng tài khoản superuser chạy `CREATE EXTENSION vector;` trong database `scanit`. Chưa có pgvector thì mọi thứ vẫn chạy, chỉ riêng quét bằng ảnh báo 503 (changeset `002-image-embeddings` tự bỏ qua; bật extension xong khởi động lại app để tạo bảng).

Tài khoản mẫu: `admin / admin123`, `nhanvien / 123456`. Schema do Liquibase tạo từ `src/main/resources/db/changelog`.
Mặc định chạy profile `dev` (khoá JWT cố định, tự tạo dữ liệu mẫu), chỉ dùng trên máy cá nhân. Production: `SPRING_PROFILES_ACTIVE=prod` và đặt `JWT_SECRET`, `DB_*` qua biến môi trường (xem `.env.example`); thiếu `JWT_SECRET` app sẽ không khởi động. Không có khoá hay mật khẩu nào trong mã nguồn.

## Lưu ảnh

Ảnh tải lên được chuẩn hoá ở server: xoay theo EXIF, thu nhỏ tối đa 1200px (không phóng to), mã hoá WebP (q80) + bản thumbnail 400px. Ảnh điện thoại 3–6MB còn khoảng 80–200KB. Tên file là SHA-256 của nội dung (`m/<sha>.webp`, `t/<sha>.webp`) nên tải trùng ảnh chỉ lưu một lần, CDN cache vĩnh viễn (`immutable`). Chỉ nhận JPEG/PNG, tối đa 12MB và 40 triệu điểm ảnh.

- `MEDIA_STORAGE=local` (mặc định): lưu vào `MEDIA_LOCAL_DIR`, phục vụ tại `/media/**`. Dùng để thử.
- `MEDIA_STORAGE=r2`: Cloudflare R2. Tạo bucket → bật Public access (hoặc gắn tên miền) → tạo API token quyền Object Read & Write → đặt `R2_ENDPOINT` (`https://<accountid>.r2.cloudflarestorage.com`), `R2_BUCKET`, `R2_ACCESS_KEY`, `R2_SECRET_KEY`, `MEDIA_PUBLIC_BASE_URL`.

Đổi nơi lưu không cần sửa dữ liệu: DB chỉ giữ `(provider, object_key)`, URL ghép lúc trả về.

## Nhận diện ảnh đời thật

```
app chụp ảnh → POST /api/scan/image → recognizer (CLIP) ra vector 512 chiều → pgvector tìm ảnh gần nhất → sản phẩm/màu
```

Dịch vụ `recognizer/` (Python, FastAPI + open_clip) chỉ làm một việc: ảnh → vector. Backend lưu vector của mọi ảnh sản phẩm khi upload (bảng `image_embeddings`) và so khớp bằng chỉ mục HNSW. Chạy: `docker compose up -d recognizer` (lần đầu build + tải model ~350MB, cần vài phút và ~2GB RAM). Ảnh đã upload khi dịch vụ chưa chạy: gọi `POST /api/admin/embeddings/reindex`.

Chất lượng phụ thuộc ảnh mẫu: mỗi màu nên có 2–4 ảnh (mặt trước, sau, cận chất liệu, người mặc). Ngưỡng `RECOGNITION_MIN_SCORE` (0.55) và `RECOGNITION_CONFIDENT_SCORE` (0.80) là giá trị khởi đầu, **cần chỉnh theo ảnh chụp thật của cửa hàng**; mỗi lượt quét ghi vào `scan_events` (kèm `confirmed_variant_id` khi nhân viên xác nhận) để đo.

## API

| Method | Đường dẫn | Quyền | Mô tả |
|---|---|---|---|
| POST | `/api/auth/login` | công khai | `{account,password}` → `{token,user{id,username,fullName,role,permissions[]}}` |
| GET | `/api/auth/me` | đăng nhập | thông tin + quyền hiện tại |
| GET | `/api/variants/by-barcode/{code}` | `scan.use` | quét mã: đúng 1 biến thể hoặc 404; khớp cả UPC-A/EAN-13 thêm số 0; ghi `scan_events` |
| GET | `/api/variants/{id}` | `product.view` | chi tiết biến thể |
| GET | `/api/variants?q=&page=&size=` | `product.view` | tìm theo tên, mã mẫu, SKU, mã vạch (size tối đa 50) |
| POST | `/api/scan/image` | `scan.use` | multipart `file` (ảnh chụp) → `{scanEventId, confident, candidates[{score, variant}]}` (tối đa 5) |
| POST | `/api/scan/{id}/confirm` | `scan.use` | `{variantId}`: xác nhận kết quả đúng |
| GET | `/api/admin/products?q=&status=&page=&size=` | `product.manage` | danh sách sản phẩm (có cả INACTIVE) |
| GET | `/api/admin/products/{id}` | `product.manage` | chi tiết đủ: biến thể, giá vốn, giá, tồn, ảnh |
| POST | `/api/admin/products` | `product.manage` | tạo sản phẩm, kèm `variants[]` (màu/size nhập tên, chưa có thì tự tạo) |
| PUT | `/api/admin/products/{id}` | `product.manage` | sửa thông tin (không đụng biến thể) |
| DELETE | `/api/admin/products/{id}` | `product.manage` | xoá mềm (giữ lịch sử kho), giải phóng mã mẫu/SKU/mã vạch |
| POST | `/api/admin/products/{id}/variants` | `product.manage` | thêm biến thể |
| PUT / DELETE | `/api/admin/variants/{id}` | `product.manage` | sửa (SKU, mã vạch, giá vốn, trạng thái, bảng giá) / xoá mềm |
| POST | `/api/admin/variants/{id}/stock` | `stock.adjust` | `{delta}` nhập/xuất hoặc `{setTo}` kiểm kho; ghi sổ `stock_movements`; 409 nếu âm |
| GET / POST | `/api/admin/products/{id}/media` | `product.manage` | liệt kê / tải ảnh (multipart `file`, `colorId` tuỳ chọn) |
| PATCH / DELETE | `/api/admin/products/{id}/media/{mediaId}` | `product.manage` | đặt ảnh chính, thứ tự / gỡ ảnh (xoá file nếu không còn nơi dùng) |
| POST | `/api/admin/embeddings/reindex` | `product.manage` | tính vector cho ảnh chưa có |
| GET | `/api/admin/colors｜sizes｜categories｜brands` | `product.manage` | danh mục tham chiếu |

Biến thể bỏ trống `sku` thì sinh `MÃMẪU-MÀU-SIZE`; bỏ trống `barcode` thì sinh EAN-13 nội bộ (đầu `200`) để in tem. Mọi thao tác quản trị ghi vào `audit_logs`.

Lỗi luôn có dạng `{code,message}`: 401 thiếu/hết hạn token, 403 thiếu quyền, 404 không thấy, 400 dữ liệu sai, 409 trùng/âm kho, 413 ảnh quá lớn, 503 dịch vụ nhận diện chưa sẵn sàng.
`costPrice` chỉ trả cho người có quyền `product.manage`.

## Khác BE cũ

- Tra mã vạch chính xác bằng index, không còn `LIKE '%x%'` rồi lọc phía client.
- Một lần gọi trả đủ: giá lẻ/sỉ, tồn kho, ảnh, màu/size còn lại của cùng sản phẩm.
- Quyền đọc lại từ DB mỗi request, khoá tài khoản có hiệu lực ngay; token chỉ chứa id.
- Thiếu token trả 401 (BE cũ trả 403 body rỗng).
- Schema do Liquibase quản lý, không dùng `ddl-auto=update`. Không còn Lombok/MapStruct/JPA.
- Secrets đọc từ môi trường.

## Chưa làm (các bước tiếp theo)

Web admin (React) dùng các API `/api/admin/*` ở trên, nhân viên và phân quyền, khách hàng, công nợ, đơn hàng, báo cáo.

## Lưu ý kiểm thử

Mã Java **chưa được biên dịch hay chạy** trong môi trường viết ra nó (không tải được Maven); mới kiểm tra cú pháp. Các câu SQL không dùng vector đã chạy thử trên PostgreSQL 16. Phần dùng thư viện ngoài (Thumbnailator, Scrimage WebP, AWS SDK cho R2, câu SQL pgvector) và dịch vụ `recognizer/` chưa được chạy thật. Lần đầu hãy chạy `mvn -q verify`, khởi động rồi thử bằng `curl` hoặc app; gặp lỗi biên dịch thì gửi nguyên văn để sửa. Test của recognizer: `python3 recognizer/tests/test_embedder.py` (cần `pillow`).
