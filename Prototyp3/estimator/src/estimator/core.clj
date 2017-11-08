(ns estimator.core
  (:require [hugsql.core :as sql]
            [clojure.pprint :as pprint]
            [clojure.java.io :as io]
            [shams.priority-queue :as pqueue]
            [clojure.data.json :as json])
  (:gen-class))

(use 'criterium.core)

(sql/def-db-fns "./estimator/sql/stops.sql")

(def infinity Double/POSITIVE_INFINITY)

(defn dijkstra-inner [neighbor distances previous queue current_stop]
  (def alt-time (+ (current_stop distances) (:travel_time neighbor)))
  (if (< alt-time ((keyword (:id neighbor)) distances))
    {:distances (into distances {(keyword (:id neighbor)) alt-time})
     :previous (merge previous {(keyword (:id neighbor)) current_stop})
     :queue (into queue [(keyword (:id neighbor))])}
    {:distances distances
     :previous previous
     :queue queue}
    ))


(defn dijkstra [from to db]
  "Finds shortest route from one stop to the other"
  (def stop_ids (map #(keyword (:stop_id %)) (stops db)))
  (def distances (reduce merge (map (fn [x]
                                        (if (= x (keyword from))
                                          {x 0}
                                          {x infinity}))
                                      stop_ids)))

  (def queue (pqueue/priority-queue #(% distances) 
                                    :elements [(keyword from)]
                                    :priority-comparator compare))
  (loop [queue queue distances distances previous {} visited #{}]
    (if (and (not (empty? queue))
             (not (= (peek queue) to))
             (not (= ((peek queue) distances) infinity)))
      (do
        (if 
             (not (contains? visited (peek queue)))
             (do     
               (def current_stop (peek queue))
               (def nn (nearest_stops db {:id (name current_stop)
                                          :time 0
                                          :moved false}))
               (def new-values (reduce (fn [acc x]
                                         {
                                          :distances (loop [a (:distances acc) b (:distances x) acc {}]
                                                       (if (not (empty? a))
                                                         (recur (rest a) (rest b) (conj acc [(first (first a))
                                                                                             (max (second (first a)) (second (first b)))]))
                                                         acc))
                                          :previous (merge (:previous acc) (:previous x))
                                          :queue (into (:queue acc) (:queue x))
                                          })
                                       {
                                        :distances distances
                                        :previous previous
                                        :queue queue
                                        } (map #(dijkstra-inner % distances previous queue current_stop) nn)))
               (recur (:queue new-values) (:distances new-values)  (:previous new-values) (conj visited current_stop)))
             (recur (pop queue) distances previous visited)))
        previous)))
        
(defn find-path [previous to]
  (defn construct-path [to path]
    (def next-stop ((keyword to) previous))
    (if (not (nil? next-stop))
      (recur next-stop (conj path to))
      path))
  (construct-path to '()))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (def config (first args))
  (def db
    (reduce merge (map (fn [x] ; Conversion to hash-map
                         (let [[k v] x]
                           (hash-map (keyword k) v)))
                       (json/read-str (slurp config)))))
  (println (find-path (dijkstra "U306Z102" "U46Z1" db) "U46Z1")))
