					; geocommit.com HTTP hook API
					; (c) 2010 The Geocommit Project
					; (c) 2010 David Soria Parra
					; Licensed under the terms of the MIT License
(ns #^{:doc "HTTP signup API functions",
       :author "David Soria Parra"}
  geocommit.hook
  (:use geocommit.core
	geocommit.config
	geocommit.http
	experimentalworks.couchdb
	clojure.contrib.logging
	clojure.contrib.json
	clojure.contrib.condition)
  (:require [appengine-magic.core :as ae]
	    [clojure.contrib.trace :as t])
  (:import (java.net URI URISyntaxException URL MalformedURLException)))

(def *couchdb* (get-config :databases :geocommits))
(defrecord Repository [_id identifier name description repository-url vcs scanned type])

(defn is-tracked?
  "Check if the given repository identifier is already tracked"
  [ident]
  (map? (couch-get *couchdb* (str "repository:" ident))))

(defn- sanitize-url
  [url]
  (try
    (str (.normalize (URI. (str (URL. url)))))
    (catch MalformedURLException mue
      (raise :type :uri-error))
    (catch URISyntaxException use
      (raise :type :uri-error))))

(defn- send-scan
  "Send a scan to the fetch service"
  [ident repository]
  (http-call-service (get-config :api :initscan)
		     {:identifier ident
		      :repository-url repository}))

(defn- scan
  "Add a scan job to the database"
  [ident name desc repourl vcs]
  (and (couch-add *couchdb*
		  (Repository.
		   (str "repository:" ident)
		   ident name desc repourl vcs false "repository"))
       (handler-case :type
	 (send-scan ident repourl)
	 (handle :service-error
	   (comment we intentionally ignore the service error and check with a
		    cronjob for unscanned jobs)
	   true))))
  
(defn- guess-origin
  "Heuristic to determine the origin of the hook request.
   Returns :github or :bitbucket"
  [payload]
  (cond
   (contains-all? payload :broker :service) :bitbucket
   (contains-all? payload :before :ref) :github))

;; Bitbucket handler
;;
(defn- bitbucket-update-parser
  "Parse bitbucket.org commits"
  [ident commits]
  (let [ctx (remove nil? (map #(parse-geocommit ident (%1 :node) (%1 :author) (%1 :message) (%1 :message)) commits))]
    (if (empty? ctx)
      nil ctx)))
					;
;; Github Handler
;;
(defn- ident-to-repository [ident]
  (str "git://" ident " .git"))

(defn- ident-from-url
  "Takes a URL like http://github.com/foo and returns a repository identifier.

   Example:
     http://github.com/dsp/geocommit -> github.com/dsp/gecommit"
  [url]
  (if url
    (.replaceFirst (sanitize-url url) "(http://|https://)" "")
    (raise :type :parse-error)))

(defn- github-api-url
  "Takes an identifier and API call location like /blob/show/ and an infinite
   number of additional parameters and builds a normalized github.com API URL.

   It is necesary to normalize a github.com API URL."
  [ident sub & rest]
  (str
   (.normalize (URI. (str
		      (get-config :api :github)
		      sub
		      (.replaceFirst ident "github\\.com/" "")
		      "/"
		      (apply str rest))))))

(defn- github-notes-commit
  "Return refs/notes/geocommit for a repository by using the fetchservice"
  [repository-url]
  (:refs/notes/geocommit
   (http-call-service (get-config :api :fetchservice)
		      {:repository-url repository-url})))

(defn- github-fetch-note
  "Return the the geocommit note for a git SHA1.
   We need the correct note-commit tree id."
  [ident note-commit id]
  (-> (http-call-service (str (github-api-url ident "/blob/show/" note-commit "/" id)))
      :blob :data))

(defn- parse-github-geocommit
  "Parse a github geocommit note. and return a geocommit struct."
  [ident note-commit commit]
  (let [{id :id {name :name mail :email} :author message :message}
	commit]
    (parse-geocommit ident id (str name " <" mail ">") message
		     (github-fetch-note ident note-commit id))))

(defn github-update-parser
  "Parse a sequence of github commits."
  [ident commits]
  (if-let [notehash (github-notes-commit (ident-to-repository ident))]
    (if-let [ctx (remove nil? (map #(parse-github-geocommit ident notehash %) commits))]
      (if (empty? ctx)
	nil ctx))))

(defn- handle-service
  [ident payload repository-url vcs parser]
  (if (is-tracked? ident)
    (if-let [ctx (parser ident (:commits payload))]
      (if-let [res (couch-bulk-update *couchdb* ctx)]
	{:status 201}
	(raise :type :service-error))
      {:status 200})
    (if (scan ident
	      (-> payload :repository :name)
	      (-> payload :repository :description)
	      repository-url
	      vcs)
      {:status 200})))

(defn- github [ident payload]
  (let [url (str "git://" ident ".git")]
    (info url)
    (handle-service ident payload url "git" github-update-parser)))

(defn- bitbucket [ident payload]
  (let [url  (sanitize-url (str "http://" ident))]
    (handle-service ident payload url "mercurial" bitbucket-update-parser)))

(defn app-hook
  "API entry point for the github/bitbucket geocommit receive service."
  [rawpayload]
  (if rawpayload
    (handler-case :type
      (try
	(if-let [payload (read-json rawpayload)]
	  (condp = (guess-origin payload)
	      :github    (github (ident-from-url (-> payload :repository :url)) payload)
	      :bitbucket (bitbucket (str "bitbucket.org"
					 (-> payload :repository :absolute_url))
				    payload))
	  (raise :type :parse-error))
	(catch Exception e
	  (raise :type :service-error
		 :message (.getMessage e))))
	(handle :parse-error
	  (error (:message "parse error"))
	  {:status 400})
	(handle :uri-error
	  {:status 400})
	(handle :service-error
	  (with-logs
	    (print-stack-trace *condition*)
	    {:status 500})))))