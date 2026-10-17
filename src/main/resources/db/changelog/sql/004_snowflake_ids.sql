-- Doi toan bo khoa chinh sang BIGINT do ung dung tu sinh (Snowflake), khong dung IDENTITY cua Postgres nua.
-- Id cu (1, 2, 3...) duoc doi thanh bigint lon: id cu + 4.000.000.000.000.000.000.
-- Id Snowflake moi (moc 2026-01-01) con rat nho so voi 4e18 nen khong bao gio trung voi id cu.
-- Cac khoa ngoai duoc doi sang ON UPDATE CASCADE de id con tu cap nhat theo.
DO $mig$
DECLARE
    base CONSTANT bigint := 4000000000000000000;
    ns   CONSTANT oid := (SELECT oid FROM pg_namespace WHERE nspname = current_schema());
    r    record;
BEGIN
    FOR r IN
        SELECT conrelid::regclass AS tbl, conname, pg_get_constraintdef(oid) AS def
        FROM pg_constraint
        WHERE contype = 'f' AND connamespace = ns
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.tbl, r.conname);
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I %s ON UPDATE CASCADE', r.tbl, r.conname, r.def);
    END LOOP;

    -- Bang co khoa chinh la 1 cot "id" kieu bigint
    FOR r IN
        SELECT c.oid::regclass AS tbl
        FROM pg_class c
        JOIN pg_index i ON i.indrelid = c.oid AND i.indisprimary AND i.indnatts = 1
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = i.indkey[0] AND a.attname = 'id'
        WHERE c.relkind = 'r' AND c.relnamespace = ns AND a.atttypid = 'int8'::regtype
    LOOP
        EXECUTE format('ALTER TABLE %s ALTER COLUMN id DROP IDENTITY IF EXISTS', r.tbl);
    END LOOP;

    FOR r IN
        SELECT c.oid::regclass AS tbl
        FROM pg_class c
        JOIN pg_index i ON i.indrelid = c.oid AND i.indisprimary AND i.indnatts = 1
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = i.indkey[0] AND a.attname = 'id'
        WHERE c.relkind = 'r' AND c.relnamespace = ns AND a.atttypid = 'int8'::regtype
    LOOP
        EXECUTE format('UPDATE %s SET id = id + %s WHERE id < %s', r.tbl, base, base);
    END LOOP;

    -- Cot tham chieu khong co khoa ngoai (da hinh: entity_id, ref_id) va id nam trong JSON
    UPDATE audit_logs      SET entity_id = entity_id + base WHERE entity_id IS NOT NULL AND entity_id < base;
    UPDATE notifications   SET entity_id = entity_id + base WHERE entity_id IS NOT NULL AND entity_id < base;
    UPDATE stock_movements SET ref_id    = ref_id + base    WHERE ref_id IS NOT NULL AND ref_id < base;
    UPDATE scan_events
       SET top_candidates = (
             SELECT jsonb_agg(CASE WHEN e ? 'variantId'
                                   THEN jsonb_set(e, '{variantId}', to_jsonb((e->>'variantId')::bigint + base))
                                   ELSE e END)
             FROM jsonb_array_elements(top_candidates) e)
     WHERE top_candidates IS NOT NULL AND jsonb_typeof(top_candidates) = 'array'
       AND jsonb_array_length(top_candidates) > 0
       AND (top_candidates->0->>'variantId')::bigint < base;
END
$mig$;
