-- 낙관적 락(@Version) 적용을 위한 version 컬럼 추가
-- 기존 행의 version이 NULL이면 JPA가 새 엔티티로 오인할 수 있으므로 0을 기본값으로 채운다
ALTER TABLE product ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
