--DROP FUNCTION IF EXISTS time_duration(interval, interval);
CREATE OR REPLACE FUNCTION time_duration(interval, interval) RETURNS INTERVAL
AS $$
DECLARE
	time_interval_a ALIAS FOR $1;
    time_interval_b ALIAS FOR $2;
BEGIN
	IF time_interval_a > time_interval_b THEN
    	RETURN time_interval_a - time_interval_b;
    ELSE
    	RETURN time_interval_b - time_interval_a;
    END IF;
END;
$$ LANGUAGE plpgsql
   IMMUTABLE;


DROP FUNCTION IF EXISTS nearest_stops(character varying, interval, bool);
CREATE OR REPLACE FUNCTION nearest_stops(varchar, interval, bool) RETURNS
TABLE(
	id VARCHAR,
	travel_time INTERVAL,
    moved BOOL
)
AS $$
DECLARE
	id ALIAS FOR $1;
	actual_time ALIAS FOR $2;
    moved ALIAS FOR $3;
BEGIN
	IF MOVED THEN 
    	RETURN QUERY
        WITH
		trips_with_stop AS (SELECT trip_id tid, stop_sequence seq
							FROM stop_times
							WHERE id = stop_id)
		SELECT stop_id sid, MIN(departure_time::INTERVAL) - actual_time travel_time, FALSE FROM trips_with_stop
								JOIN stop_times ON tid = trip_id
								WHERE stop_sequence = (seq + 1) AND departure_time::INTERVAL >= actual_time
                GROUP BY sid;
   	ELSE
		RETURN QUERY
            WITH
                trips_with_stop AS (SELECT trip_id tid, stop_sequence seq
                                    FROM stop_times
                                    WHERE id = stop_id),
                stops_in_path AS (SELECT stop_id sid, MIN(departure_time::INTERVAL) - actual_time travel_time, FALSE FROM trips_with_stop
                                        JOIN stop_times ON tid = trip_id
                                        WHERE stop_sequence = (seq + 1) AND departure_time::INTERVAL >= actual_time
                        		 GROUP BY sid)
                        SELECT * FROM stops_in_path
                        UNION
                        SELECT s2_id, time, TRUE FROM travel_time WHERE s1_id = id AND s2_id NOT IN (SELECT sid FROM stops_in_path) AND time <= INTERVAL '00:00:30';                        
	END IF;
END;
$$ LANGUAGE plpgsql
   STABLE;

DROP FUNCTION IF EXISTS dijkstra(character varying, character varying, interval) CASCADE;
CREATE OR REPLACE FUNCTION dijkstra(from_id varchar, to_id varchar, actual_time interval) RETURNS
TABLE(
	id VARCHAR,
	travel_time INTERVAL
)
AS $$
DECLARE
	--from_id ALIAS FOR $1;
	--to_id ALIAS FOR $2;
	--actual_time ALIAS FOR $3;
	nearest_stop RECORD;
	alternative_travel_time INTERVAL;
	neighbor RECORD;
BEGIN
		DROP TABLE IF EXISTS distances;
		DROP TABLE IF EXISTS previous_stops;
		DROP TABLE IF EXISTS priority_queue;
		-- Assign to every node a tentative distance value: set it to zero for our initial node and to infinity for all other nodes.
		CREATE TEMPORARY TABLE distances AS
			(SELECT stop_id, CASE stop_id
								WHEN from_id THEN INTERVAL '00:00:00'
								ELSE INTERVAL '99:59:59'
							END	AS distance
							FROM stops);
        --CREATE INDEX CONCURRENTLY stop_idx ON distances (stop_id);
                            
		CREATE TEMPORARY TABLE previous_stops AS
          SELECT stop_id origin, ''::VARCHAR destination FROM stops;

		CREATE TEMPORARY TABLE priority_queue AS 
        	SELECT stop_id, False moved FROM stops;
        
        
		WHILE (SELECT COUNT(*) FROM priority_queue LIMIT 1) != 0 LOOP
            SELECT INTO nearest_stop p.stop_id, d.distance, moved FROM priority_queue p JOIN distances d ON p.stop_id = d.stop_id ORDER BY distance ASC LIMIT 1;
            IF nearest_stop.stop_id = to_id OR nearest_stop.distance >= INTERVAL '99:59:59' THEN
              exit;
            END IF;
            DELETE FROM priority_queue p WHERE p.stop_id = nearest_stop.stop_id;
            FOR neighbor IN SELECT * FROM nearest_stops(nearest_stop.stop_id, actual_time + nearest_stop.distance, nearest_stop.moved) n
            				JOIN distances d ON n.id = d.stop_id
            LOOP
                UPDATE priority_queue SET moved = neighbor.moved WHERE stop_id = neighbor.id;
                alternative_travel_time := nearest_stop.distance + time_duration(neighbor.travel_time, nearest_stop.distance);
                IF alternative_travel_time < neighbor.distance THEN
                   UPDATE distances SET distance = alternative_travel_time WHERE stop_id = neighbor.id;
                   UPDATE previous_stops SET destination = nearest_stop.stop_id WHERE origin = neighbor.id;
                END IF;
            END LOOP;
		END LOOP;

    RETURN QUERY
      	WITH RECURSIVE stop_path(id) AS (
              SELECT to_id
              UNION ALL
              SELECT prev.destination FROM stop_path p, previous_stops prev WHERE p.id = prev.origin AND prev.origin != ''
            )
            SELECT s.id, distance FROM stop_path s
            	JOIN distances d
            	ON s.id = d.stop_id;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS find_travel_time_between_two_points(float, float, float, float);
CREATE OR REPLACE FUNCTION find_travel_time_between_two_points(from_lat float, from_lng float, to_lat float, to_lng float) RETURNS INTEGER
AS
$$
DECLARE
origin RECORD;
destination RECORD;
BEGIN
	SELECT INTO origin 
        stop_id sid, ROUND((geom::geography <-> ST_SetSRID(ST_Point(from_lng, from_lat), 4326)::geography) / 1.4)::VARCHAR::INTERVAL travel_time
        FROM stops ORDER BY geom::geography <-> ST_SetSRID(ST_Point(from_lng, from_lat), 4326)::geography ASC LIMIT 1;
        
    SELECT INTO destination 
        stop_id sid, ROUND((geom::geography <-> ST_SetSRID(ST_Point(to_lng, to_lat), 4326)::geography) / 1.4)::VARCHAR::INTERVAL travel_time
        FROM stops ORDER BY geom::geography <-> ST_SetSRID(ST_Point(to_lng, to_lat), 4326)::geography ASC LIMIT 1;
    
	RETURN 
    	(
            SELECT EXTRACT(EPOCH FROM max(travel_time) + origin.travel_time + destination.travel_time)
            FROM dijkstra(origin.sid, destination.sid, '11:00:00'));
END;
$$ LANGUAGE plpgsql;
