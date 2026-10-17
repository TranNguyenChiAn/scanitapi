package com.scanit.api.common;

import com.scanit.api.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Sinh id Snowflake 64 bit, tang dan theo thoi gian (chen vao B-tree o cuoi chi muc, khong xao tron nhu UUID v4):
 * <pre>
 *  1 bit 0 | 41 bit mili-giay ke tu 2026-01-01 (~69 nam) | 10 bit may chu (0..1023) | 12 bit so thu tu (4096 id / ms / may)
 * </pre>
 * Moi tien trinh backend dang chay dong thoi PHAI co worker-id khac nhau (SNOWFLAKE_WORKER_ID), neu khong id se trung.
 * Sinh id ngay trong bo nho nen khong ton them lan goi DB nao.
 */
@Component
public class IdGenerator {

    /** 2026-01-01T00:00:00Z */
    static final long EPOCH = 1_767_225_600_000L;
    private static final int WORKER_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_WORKER = (1L << WORKER_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    /** Dong ho lui toi da bao nhieu ms thi cho; lui hon thi tu choi (tranh sinh id trung). */
    private static final long MAX_BACKWARD_WAIT_MS = 50;

    private final long workerId;
    private long lastMs = -1L;
    private long sequence = 0L;

    @Autowired // co 2 constructor: chi dinh constructor cho Spring dung
    public IdGenerator(AppProperties props) {
        this(props.snowflake() == null ? 1 : props.snowflake().workerId());
    }

    public IdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER) {
            throw new IllegalStateException("SNOWFLAKE_WORKER_ID phai trong khoang 0.." + MAX_WORKER + ", dang la " + workerId);
        }
        this.workerId = workerId;
    }

    public synchronized long next() {
        long now = System.currentTimeMillis();
        if (now < lastMs) {
            long behind = lastMs - now;
            if (behind > MAX_BACKWARD_WAIT_MS) {
                throw new IllegalStateException("Dong ho he thong bi lui " + behind + "ms, khong the sinh id an toan.");
            }
            now = waitUntil(lastMs);
        }
        if (now == lastMs) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) now = waitUntil(lastMs + 1); // het so thu tu trong ms nay
        } else {
            sequence = 0;
        }
        lastMs = now;
        return ((now - EPOCH) << (WORKER_BITS + SEQUENCE_BITS)) | (workerId << SEQUENCE_BITS) | sequence;
    }

    private static long waitUntil(long ms) {
        long now = System.currentTimeMillis();
        while (now < ms) {
            Thread.onSpinWait();
            now = System.currentTimeMillis();
        }
        return now;
    }

    /** Thoi diem id duoc sinh (de debug). */
    public static Instant timestampOf(long id) {
        return Instant.ofEpochMilli((id >>> (WORKER_BITS + SEQUENCE_BITS)) + EPOCH);
    }
}
