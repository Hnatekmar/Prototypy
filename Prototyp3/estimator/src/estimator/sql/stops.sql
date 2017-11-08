-- :name stops
-- :command query
-- :result raw
-- :doc Selects all stops
SELECT stop_id FROM stops;

-- :name nearest_stops
-- :command query
-- :result raw
-- :doc Returns stop that are next in graph
SELECT id, EXTRACT(EPOCH FROM travel_time) AS travel_time, moved FROM nearest_stops(:id, :time::VARCHAR::INTERVAL, :moved);
