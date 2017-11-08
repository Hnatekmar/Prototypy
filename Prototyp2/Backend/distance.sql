DROP TABLE IF EXISTS travel_time;
CREATE TABLE travel_time
AS
SELECT s1.stop_id as s1_id, s2.stop_id as s2_id, ROUND(ST_Distance(s1.geom::geography, s2.geom::geography, False) / 1.4)::VARCHAR::INTERVAL as time
	FROM stops s1 JOIN (SELECT * FROM stops LIMIT 1) s2 ON s1.stop_id != s2.stop_id