CREATE SCHEMA analytics;
CREATE TABLE analytics.orders (
 order_id bigint PRIMARY KEY,
 order_date date NOT NULL,
 category text NOT NULL CHECK(category IN ('ELECTRONICS','HOME','CLOTHING','GROCERY','SPORTS')),
 region text NOT NULL CHECK(region IN ('NORTH','SOUTH','EAST','WEST','CENTRAL')),
 status text NOT NULL CHECK(status IN ('DELIVERED','PENDING','CANCELLED')),
 order_amount numeric(12,2) NOT NULL CHECK(order_amount>=0),
 returned boolean NOT NULL
);
INSERT INTO analytics.orders
SELECT i, DATE '2026-01-31' - ((i * 17) % 180),
 (ARRAY['ELECTRONICS','HOME','CLOTHING','GROCERY','SPORTS'])[1+(i%5)],
 (ARRAY['NORTH','SOUTH','EAST','WEST','CENTRAL'])[1+((i/5)%5)],
 (ARRAY['DELIVERED','PENDING','CANCELLED'])[1+(i%3)],
 ((i*7919)%90000+100)::numeric/100, i%11=0
FROM generate_series(1,1000) i;
REVOKE ALL ON SCHEMA analytics FROM PUBLIC;
REVOKE ALL ON analytics.orders FROM PUBLIC;
GRANT USAGE ON SCHEMA analytics TO analytics;
GRANT SELECT ON analytics.orders TO analytics;
