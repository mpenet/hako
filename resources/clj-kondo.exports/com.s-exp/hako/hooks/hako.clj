(ns hooks.hako
  "clj-kondo hooks for s-exp.hako.

  Ships as a library-provided config that consumers pick up
  automatically when they run `clj-kondo --lint ... --copy-configs`."
  (:require [clj-kondo.hooks-api :as api]))

(defn- fn-arity
  "Return the arity of a literal fn node `(fn [args...] body)` or
  `#(...)` — nil if not analyzable."
  [node]
  (when (and node (or (api/list-node? node) (api/vector-node? node)))
    (let [form (try (api/sexpr node) (catch Exception _ nil))]
      (cond
        (and (seq? form)
             (contains? #{'fn 'fn* 'clojure.core/fn} (first form)))
        (let [args (some #(when (vector? %) %) (rest form))]
          (when args (count args)))

        :else nil))))

(defn register-user-tag!
  "Verify that write-fn is 2-arity [writer value] and read-fn is
  1-arity [reader]."
  [{:keys [node]}]
  (let [[_ _id _klass write-fn read-fn] (:children node)]
    (when-let [n (fn-arity write-fn)]
      (when (not= 2 n)
        (api/reg-finding!
         (assoc (meta write-fn)
                :message (str "hako: register-user-tag! write-fn expects 2 args [writer value], got " n)
                :type :hako/user-tag-arity))))
    (when-let [n (fn-arity read-fn)]
      (when (not= 1 n)
        (api/reg-finding!
         (assoc (meta read-fn)
                :message (str "hako: register-user-tag! read-fn expects 1 arg [reader], got " n)
                :type :hako/user-tag-arity)))))
  {:node node})
