-- Quyen sua gia ban tung dong khi tao don; chi ADMIN co. Nhan vien ban theo bang gia.
-- (id do ung dung cap tu 004, nen lay MAX+1 cho dong quyen moi)
INSERT INTO permissions (id, code, module, description)
SELECT (SELECT COALESCE(MAX(id), 0) + 1 FROM permissions), 'order.price', 'order', 'Sua gia ban tung dong khi tao don'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE code = 'order.price');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'order.price'
WHERE r.code = 'ADMIN'
ON CONFLICT DO NOTHING;
