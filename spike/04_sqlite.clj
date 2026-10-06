#!/usr/bin/env bb
;; Spike 0.5 — SQLite via pod-babashka-go-sqlite3
;; Goal: load the pod, create a table, insert, query.
;; Run: bb spike/04_sqlite.clj
;; https://github.com/babashka/pod-babashka-go-sqlite3

(require '[babashka.pods :as pods])
(pods/load-pod 'org.babashka/go-sqlite3 "0.2.5")
(require '[pod.babashka.go-sqlite3 :as sqlite])

(def db "/tmp/toto-spike.db")
(babashka.fs/delete-if-exists db)

(sqlite/execute! db ["create table sessions (id text primary key, created text, model text)"])
(sqlite/execute! db ["insert into sessions (id, created, model) values (?,?,?)"
                     "s-1" "2026-06-16" "claude-opus"])
(sqlite/execute! db ["insert into sessions (id, created, model) values (?,?,?)"
                     "s-2" "2026-06-16" "claude-haiku"])

(def rows (sqlite/query db ["select * from sessions order by id"]))
(println "rows:" rows)

(assert (= 2 (count rows)))
(assert (= "claude-opus" (:model (first rows))))
(babashka.fs/delete-if-exists db)
(println "\nSPIKE 0.5 SQLite pod: PASS")
