;;  Copyright (c) Eduardo Julian. All rights reserved.
;;  This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
;;  If a copy of the MPL was not distributed with this file,
;;  You can obtain one at http://mozilla.org/MPL/2.0/.

(ns lux.type
  (:refer-clojure :exclude [deref apply merge bound?])
  (:require [clojure.template :refer [do-template]]
            clojure.core.match
            clojure.core.match.array
            (lux [base :as & :refer [|do return* return fail fail* assert! |let |case]])
            [lux.type.host :as &&host]))

(declare show-type)

;; [Utils]
(defn |list? [xs]
  (|case xs
    (&/$Nil)
    true

    (&/$Cons x xs*)
    (|list? xs*)

    _
    false))

(def empty-env &/Nil$)
(defn Data$ [name params]
  (&/V &/$DataT (&/T [name params])))
(defn Bound$ [idx]
  (&/V &/$BoundT idx))
(defn Var$ [id]
  (&/V &/$VarT id))
(defn Lambda$ [in out]
  (&/V &/$LambdaT (&/T [in out])))
(defn App$ [fun arg]
  (&/V &/$AppT (&/T [fun arg])))
(defn Prod$ [left right]
  (&/V &/$ProdT (&/T [left right])))
(defn Sum$ [left right]
  (&/V &/$SumT (&/T [left right])))
(defn Univ$ [env body]
  (&/V &/$UnivQ (&/T [env body])))
(defn Ex$ [env body]
  (&/V &/$ExQ (&/T [env body])))
(defn Named$ [name type]
  (&/V &/$NamedT (&/T [name type])))

(def $Void (&/V &/$VoidT nil))
(def Unit (&/V &/$UnitT nil))
(def Bool (Named$ (&/T ["lux" "Bool"]) (Data$ "java.lang.Boolean" &/Nil$)))
(def Int (Named$ (&/T ["lux" "Int"]) (Data$ "java.lang.Long" &/Nil$)))
(def Real (Named$ (&/T ["lux" "Real"]) (Data$ "java.lang.Double" &/Nil$)))
(def Char (Named$ (&/T ["lux" "Char"]) (Data$ "java.lang.Character" &/Nil$)))
(def Text (Named$ (&/T ["lux" "Text"]) (Data$ "java.lang.String" &/Nil$)))
(def Ident (Named$ (&/T ["lux" "Ident"]) (Prod$ Text Text)))

(def IO
  (Named$ (&/T ["lux/data" "IO"])
          (Univ$ empty-env
                 (Lambda$ Unit (Bound$ 1)))))

(def List
  (Named$ (&/T ["lux" "List"])
          (Univ$ empty-env
                 (Sum$
                  ;; lux;Nil
                  Unit
                  ;; lux;Cons
                  (Prod$ (Bound$ 1)
                         (App$ (Bound$ 0)
                               (Bound$ 1)))))))

(def Maybe
  (Named$ (&/T ["lux" "Maybe"])
          (Univ$ empty-env
                 (Sum$
                  ;; lux;None
                  Unit
                  ;; lux;Some
                  (Bound$ 1))
                 )))

(def Type
  (Named$ (&/T ["lux" "Type"])
          (let [Type (App$ (Bound$ 0) (Bound$ 1))
                TypeList (App$ List Type)
                TypePair (Prod$ Type Type)]
            (App$ (Univ$ empty-env
                         (Sum$
                          ;; DataT
                          (Prod$ Text TypeList)
                          (Sum$
                           ;; VoidT
                           Unit
                           (Sum$
                            ;; UnitT
                            Unit
                            (Sum$
                             ;; SumT
                             TypePair
                             (Sum$
                              ;; ProdT
                              TypePair
                              (Sum$
                               ;; LambdaT
                               TypePair
                               (Sum$
                                ;; BoundT
                                Int
                                (Sum$
                                 ;; VarT
                                 Int
                                 (Sum$
                                  ;; ExT
                                  Int
                                  (Sum$
                                   ;; UnivQ
                                   (Prod$ TypeList Type)
                                   (Sum$
                                    ;; ExQ
                                    (Prod$ TypeList Type)
                                    (Sum$
                                     ;; AppT
                                     TypePair
                                     ;; NamedT
                                     (Prod$ Ident Type)))))))))))))
                         )
                  $Void))))

(def DefMetaValue
  (Named$ (&/T ["lux" "DefMetaValue"])
          (let [DefMetaValue (App$ (Bound$ 0) (Bound$ 1))]
            (App$ (Univ$ empty-env
                         (Sum$
                          ;; BoolM
                          Bool
                          (Sum$
                           ;; IntM
                           Int
                           (Sum$
                            ;; RealM
                            Real
                            (Sum$
                             ;; CharM
                             Char
                             (Sum$
                              ;; TextM
                              Text
                              (Sum$
                               ;; IdentM
                               Ident
                               (Sum$
                                ;; ListM
                                (App$ List DefMetaValue)
                                ;; DictM
                                (App$ List (Prod$ Text DefMetaValue)))))))))
                         )
                  $Void))))

(def DefMeta
  (Named$ (&/T ["lux" "DefMeta"])
          (App$ List (Prod$ Ident DefMetaValue))))

(def Macro)

(defn set-macro-type! [type]
  (def Macro type)
  nil)

(defn bound? [id]
  (fn [state]
    (if-let [type (->> state (&/get$ &/$type-vars) (&/get$ &/$mappings) (&/|get id))]
      (|case type
        (&/$Some type*)
        (return* state true)
        
        (&/$None)
        (return* state false))
      (fail* (str "[Type Error] <bound?> Unknown type-var: " id)))))

(defn deref [id]
  (fn [state]
    (if-let [type* (->> state (&/get$ &/$type-vars) (&/get$ &/$mappings) (&/|get id))]
      (|case type*
        (&/$Some type)
        (return* state type)
        
        (&/$None)
        (fail* (str "[Type Error] Unbound type-var: " id)))
      (fail* (str "[Type Error] <deref> Unknown type-var: " id)))))

(defn deref+ [type]
  (|case type
    (&/$VarT id)
    (deref id)

    _
    (fail (str "[Type Error] Type is not a variable: " (show-type type)))
    ))

(defn set-var [id type]
  (fn [state]
    (if-let [tvar (->> state (&/get$ &/$type-vars) (&/get$ &/$mappings) (&/|get id))]
      (|case tvar
        (&/$Some bound)
        (fail* (str "[Type Error] Can't rebind type var: " id " | Current type: " (show-type bound)))
        
        (&/$None)
        (return* (&/update$ &/$type-vars (fn [ts] (&/update$ &/$mappings #(&/|put id (&/V &/$Some type) %)
                                                            ts))
                            state)
                 nil))
      (fail* (str "[Type Error] <set-var> Unknown type-var: " id " | " (->> state (&/get$ &/$type-vars) (&/get$ &/$mappings) &/|length))))))

;; [Exports]
;; Type vars
(def ^:private create-var
  (fn [state]
    (let [id (->> state (&/get$ &/$type-vars) (&/get$ &/$counter))]
      (return* (&/update$ &/$type-vars #(->> %
                                             (&/update$ &/$counter inc)
                                             (&/update$ &/$mappings (fn [ms] (&/|put id &/None$ ms))))
                          state)
               id))))

(def existential
  ;; (Lux Type)
  (|do [seed &/gen-id]
    (return (&/V &/$ExT seed))))

(declare clean*)
(defn ^:private delete-var [id]
  (|do [? (bound? id)
        _ (if ?
            (return nil)
            (|do [ex existential]
              (set-var id ex)))]
    (fn [state]
      ((|do [mappings* (&/map% (fn [binding]
                                 (|let [[?id ?type] binding]
                                   (if (.equals ^Object id ?id)
                                     (return binding)
                                     (|case ?type
                                       (&/$None)
                                       (return binding)

                                       (&/$Some ?type*)
                                       (|case ?type*
                                         (&/$VarT ?id*)
                                         (if (.equals ^Object id ?id*)
                                           (return (&/T [?id &/None$]))
                                           (return binding))

                                         _
                                         (|do [?type** (clean* id ?type*)]
                                           (return (&/T [?id (&/V &/$Some ?type**)]))))
                                       ))))
                               (->> state (&/get$ &/$type-vars) (&/get$ &/$mappings)))]
         (fn [state]
           (return* (&/update$ &/$type-vars #(&/set$ &/$mappings (&/|remove id mappings*) %)
                               state)
                    nil)))
       state))))

(defn with-var [k]
  (|do [id create-var
        output (k (Var$ id))
        _ (delete-var id)]
    (return output)))

(defn clean* [?tid type]
  (|case type
    (&/$VarT ?id)
    (if (.equals ^Object ?tid ?id)
      (|do [? (bound? ?id)]
        (if ?
          (deref ?id)
          (return type)))
      (return type))
    
    (&/$LambdaT ?arg ?return)
    (|do [=arg (clean* ?tid ?arg)
          =return (clean* ?tid ?return)]
      (return (Lambda$ =arg =return)))

    (&/$AppT ?lambda ?param)
    (|do [=lambda (clean* ?tid ?lambda)
          =param (clean* ?tid ?param)]
      (return (App$ =lambda =param)))

    (&/$ProdT ?left ?right)
    (|do [=left (clean* ?tid ?left)
          =right (clean* ?tid ?right)]
      (return (Prod$ =left =right)))
    
    (&/$SumT ?left ?right)
    (|do [=left (clean* ?tid ?left)
          =right (clean* ?tid ?right)]
      (return (Sum$ =left =right)))

    (&/$UnivQ ?env ?body)
    (|do [=env (&/map% (partial clean* ?tid) ?env)
          body* (clean* ?tid ?body)]
      (return (Univ$ =env body*)))

    _
    (return type)
    ))

(defn clean [tvar type]
  (|case tvar
    (&/$VarT ?id)
    (clean* ?id type)
    
    _
    (fail (str "[Type Error] Not type-var: " (show-type tvar)))))

(defn ^:private unravel-fun [type]
  (|case type
    (&/$LambdaT ?in ?out)
    (|let [[??out ?args] (unravel-fun ?out)]
      (&/T [??out (&/Cons$ ?in ?args)]))

    _
    (&/T [type &/Nil$])))

(defn ^:private unravel-app [fun-type]
  (|case fun-type
    (&/$AppT ?left ?right)
    (|let [[?fun-type ?args] (unravel-app ?left)]
      (&/T [?fun-type (&/|++ ?args (&/|list ?right))]))

    _
    (&/T [fun-type &/Nil$])))

(do-template [<tag> <flatten> <at> <desc>]
  (do (defn <flatten> [type]
        "(-> Type (List Type))"
        (|case type
          (<tag> left right)
          (&/Cons$ left (<flatten> right))

          _
          (&/|list type)))

    (defn <at> [tag type]
      "(-> Int Type (Lux Type))"
      (|case type
        (&/$NamedT ?name ?type)
        (<at> tag ?type)
        
        (<tag> ?left ?right)
        (|case (&/T [tag ?right])
          [0 _]                (return ?left)
          [1 (<tag> ?left* _)] (return ?left*)
          [1 _]                (return ?right)
          [_ (<tag> _ _)]      (<at> (dec tag) ?right)
          _                    (fail (str "[Type Error] " <desc> " lacks member: " tag " | " (show-type type))))

        _
        (fail (str "[Type Error] Type is not a " <desc> ": " (show-type type))))))

  &/$SumT  flatten-sum  sum-at "Sum"
  &/$ProdT flatten-prod prod-at "Product"
  )

(defn show-type [^objects type]
  (|case type
    (&/$DataT name params)
    (|case params
      (&/$Nil)
      (str "(^ " name ")")

      _
      (str "(^ " name " " (->> params (&/|map show-type) (&/|interpose " ") (&/fold str "")) ")"))

    (&/$VoidT)
    "Void"
    
    (&/$UnitT)
    "Unit"
    
    (&/$ProdT _)
    (str "(, " (->> (flatten-prod type) (&/|map show-type) (&/|interpose " ") (&/fold str "")) ")")

    (&/$SumT _)
    (str "(|| " (->> (flatten-sum type) (&/|map show-type) (&/|interpose " ") (&/fold str "")) ")")
    
    (&/$LambdaT input output)
    (|let [[?out ?ins] (unravel-fun type)]
      (str "(-> " (->> ?ins (&/|map show-type) (&/|interpose " ") (&/fold str "")) " " (show-type ?out) ")"))

    (&/$VarT id)
    (str "⌈" id "⌋")

    (&/$ExT ?id)
    (str "⟨" ?id "⟩")

    (&/$BoundT idx)
    (str idx)

    (&/$AppT _ _)
    (|let [[?call-fun ?call-args] (unravel-app type)]
      (str "(" (show-type ?call-fun) " " (->> ?call-args (&/|map show-type) (&/|interpose " ") (&/fold str "")) ")"))
    
    (&/$UnivQ ?env ?body)
    (str "(All " (show-type ?body) ")")

    (&/$ExQ ?env ?body)
    (str "(Ex " (show-type ?body) ")")
    
    (&/$NamedT ?name ?type)
    (&/ident->text ?name)

    _
    (assert false (prn-str 'show-type (&/adt->text type)))))

(defn type= [x y]
  (or (clojure.lang.Util/identical x y)
      (let [output (|case [x y]
                     [(&/$NamedT [?xmodule ?xname] ?xtype) (&/$NamedT [?ymodule ?yname] ?ytype)]
                     (and (= ?xmodule ?ymodule)
                          (= ?xname ?yname))

                     [(&/$DataT xname xparams) (&/$DataT yname yparams)]
                     (and (.equals ^Object xname yname)
                          (= (&/|length xparams) (&/|length yparams))
                          (&/fold2 #(and %1 (type= %2 %3)) true xparams yparams))

                     [(&/$VoidT) (&/$VoidT)]
                     true
                     
                     [(&/$UnitT) (&/$UnitT)]
                     true

                     [(&/$ProdT xL xR) (&/$ProdT yL yR)]
                     (and (type= xL yL)
                          (type= xR yR))

                     [(&/$SumT xL xR) (&/$SumT yL yR)]
                     (and (type= xL yL)
                          (type= xR yR))

                     [(&/$LambdaT xinput xoutput) (&/$LambdaT yinput youtput)]
                     (and (type= xinput yinput)
                          (type= xoutput youtput))

                     [(&/$VarT xid) (&/$VarT yid)]
                     (.equals ^Object xid yid)

                     [(&/$BoundT xidx) (&/$BoundT yidx)]
                     (= xidx yidx)

                     [(&/$ExT xid) (&/$ExT yid)]
                     (.equals ^Object xid yid)

                     [(&/$AppT xlambda xparam) (&/$AppT ylambda yparam)]
                     (and (type= xlambda ylambda) (type= xparam yparam))
                     
                     [(&/$UnivQ xenv xbody) (&/$UnivQ yenv ybody)]
                     (type= xbody ybody)

                     [(&/$NamedT ?xname ?xtype) _]
                     (type= ?xtype y)

                     [_ (&/$NamedT ?yname ?ytype)]
                     (type= x ?ytype)
                     
                     [_ _]
                     false
                     )]
        output)))

(defn ^:private fp-get [k fixpoints]
  (|let [[e a] k]
    (|case fixpoints
      (&/$Nil)
      &/None$

      (&/$Cons [[e* a*] v*] fixpoints*)
      (if (and (type= e e*)
               (type= a a*))
        (&/V &/$Some v*)
        (fp-get k fixpoints*))
      )))

(defn ^:private fp-put [k v fixpoints]
  (&/Cons$ (&/T [k v]) fixpoints))

(defn ^:private show-type+ [type]
  (|case type
    (&/$VarT ?id)
    (fn [state]
      (|case ((deref ?id) state)
        (&/$Right state* bound)
        (return* state (str (show-type type) " = " (show-type bound)))

        (&/$Left _)
        (return* state (show-type type))))

    _
    (return (show-type type))))

(defn ^:private check-error [err expected actual]
  (|do [=expected (show-type+ expected)
        =actual (show-type+ actual)]
    (fail (str (if (= "" err) err (str err "\n"))
               "[Type Checker]\n"
               "Expected: " =expected "\n\n"
               "Actual:   " =actual
               "\n"))))

(defn beta-reduce [env type]
  (|case type
    (&/$DataT ?name ?params)
    (Data$ ?name (&/|map (partial beta-reduce env) ?params))

    (&/$SumT ?left ?right)
    (let [=left (beta-reduce env ?left)
          =right (beta-reduce env ?right)]
      (Sum$ =left =right))

    (&/$ProdT ?left ?right)
    (let [=left (beta-reduce env ?left)
          =right (beta-reduce env ?right)]
      (Prod$ =left =right))

    (&/$AppT ?type-fn ?type-arg)
    (App$ (beta-reduce env ?type-fn) (beta-reduce env ?type-arg))

    (&/$UnivQ ?local-env ?local-def)
    (|case ?local-env
      (&/$Nil)
      (Univ$ env ?local-def)

      _
      type)

    (&/$LambdaT ?input ?output)
    (Lambda$ (beta-reduce env ?input) (beta-reduce env ?output))

    (&/$BoundT ?idx)
    (|case (&/|at ?idx env)
      (&/$Some bound)
      (beta-reduce env bound)

      _
      (assert false (str "[Type Error] Unknown var: " ?idx " | " (&/->seq (&/|map show-type env)))))

    _
    type
    ))

(defn apply-type [type-fn param]
  (|case type-fn
    (&/$UnivQ local-env local-def)
    (return (beta-reduce (->> local-env
                              (&/Cons$ param)
                              (&/Cons$ type-fn))
                         local-def))

    (&/$ExQ local-env local-def)
    (return (beta-reduce (->> local-env
                              (&/Cons$ param)
                              (&/Cons$ type-fn))
                         local-def))

    (&/$AppT F A)
    (|do [type-fn* (apply-type F A)]
      (apply-type type-fn* param))

    (&/$NamedT ?name ?type)
    (apply-type ?type param)

    (&/$ExT id)
    (return (App$ type-fn param))
    
    _
    (fail (str "[Type System] Not a type function:\n" (show-type type-fn) "\n"))))

(def ^:private init-fixpoints &/Nil$)

(defn ^:private check* [class-loader fixpoints invariant?? expected actual]
  (if (clojure.lang.Util/identical expected actual)
    (return (&/T [fixpoints nil]))
    (&/with-attempt
      (|case [expected actual]
        [(&/$VarT ?eid) (&/$VarT ?aid)]
        (if (.equals ^Object ?eid ?aid)
          (return (&/T [fixpoints nil]))
          (|do [ebound (fn [state]
                         (|case ((deref ?eid) state)
                           (&/$Right state* ebound)
                           (return* state* (&/V &/$Some ebound))

                           (&/$Left _)
                           (return* state &/None$)))
                abound (fn [state]
                         (|case ((deref ?aid) state)
                           (&/$Right state* abound)
                           (return* state* (&/V &/$Some abound))

                           (&/$Left _)
                           (return* state &/None$)))]
            (|case [ebound abound]
              [(&/$None _) (&/$None _)]
              (|do [_ (set-var ?eid actual)]
                (return (&/T [fixpoints nil])))
              
              [(&/$Some etype) (&/$None _)]
              (check* class-loader fixpoints invariant?? etype actual)

              [(&/$None _) (&/$Some atype)]
              (check* class-loader fixpoints invariant?? expected atype)

              [(&/$Some etype) (&/$Some atype)]
              (check* class-loader fixpoints invariant?? etype atype))))
        
        [(&/$VarT ?id) _]
        (fn [state]
          (|case ((set-var ?id actual) state)
            (&/$Right state* _)
            (return* state* (&/T [fixpoints nil]))

            (&/$Left _)
            ((|do [bound (deref ?id)]
               (check* class-loader fixpoints invariant?? bound actual))
             state)))
        
        [_ (&/$VarT ?id)]
        (fn [state]
          (|case ((set-var ?id expected) state)
            (&/$Right state* _)
            (return* state* (&/T [fixpoints nil]))

            (&/$Left _)
            ((|do [bound (deref ?id)]
               (check* class-loader fixpoints invariant?? expected bound))
             state)))

        [(&/$AppT (&/$ExT eid) eA) (&/$AppT (&/$ExT aid) aA)]
        (if (= eid aid)
          (check* class-loader fixpoints invariant?? eA aA)
          (check-error "" expected actual))

        [(&/$AppT (&/$VarT ?id) A1) (&/$AppT F2 A2)]
        (fn [state]
          (|case ((|do [F1 (deref ?id)]
                    (check* class-loader fixpoints invariant?? (App$ F1 A1) actual))
                  state)
            (&/$Right state* output)
            (return* state* output)

            (&/$Left _)
            ((|do [[fixpoints* _] (check* class-loader fixpoints invariant?? (Var$ ?id) F2)
                   e* (apply-type F2 A1)
                   a* (apply-type F2 A2)
                   [fixpoints** _] (check* class-loader fixpoints* invariant?? e* a*)]
               (return (&/T [fixpoints** nil])))
             state)))
        
        [(&/$AppT F1 A1) (&/$AppT (&/$VarT ?id) A2)]
        (fn [state]
          (|case ((|do [F2 (deref ?id)]
                    (check* class-loader fixpoints invariant?? expected (App$ F2 A2)))
                  state)
            (&/$Right state* output)
            (return* state* output)

            (&/$Left _)
            ((|do [[fixpoints* _] (check* class-loader fixpoints invariant?? F1 (Var$ ?id))
                   e* (apply-type F1 A1)
                   a* (apply-type F1 A2)
                   [fixpoints** _] (check* class-loader fixpoints* invariant?? e* a*)]
               (return (&/T [fixpoints** nil])))
             state)))
        
        [(&/$AppT F A) _]
        (let [fp-pair (&/T [expected actual])
              _ (when (> (&/|length fixpoints) 40)
                  (println 'FIXPOINTS (->> (&/|keys fixpoints)
                                           (&/|map (fn [pair]
                                                     (|let [[e a] pair]
                                                       (str (show-type e) ":+:"
                                                            (show-type a)))))
                                           (&/|interpose "\n\n")
                                           (&/fold str "")))
                  (assert false (prn-str 'check* '[(&/$AppT F A) _] (&/|length fixpoints) (show-type expected) (show-type actual))))]
          (|case (fp-get fp-pair fixpoints)
            (&/$Some ?)
            (if ?
              (return (&/T [fixpoints nil]))
              (check-error "" expected actual))

            (&/$None)
            (|do [expected* (apply-type F A)]
              (check* class-loader (fp-put fp-pair true fixpoints) invariant?? expected* actual))))

        [_ (&/$AppT F A)]
        (|do [actual* (apply-type F A)]
          (check* class-loader fixpoints invariant?? expected actual*))

        [(&/$UnivQ _) _]
        (|do [$arg existential
              expected* (apply-type expected $arg)]
          (check* class-loader fixpoints invariant?? expected* actual))
        
        [_ (&/$UnivQ _)]
        (with-var
          (fn [$arg]
            (|do [actual* (apply-type actual $arg)]
              (check* class-loader fixpoints invariant?? expected actual*))))

        [(&/$ExQ e!env e!def) _]
        (with-var
          (fn [$arg]
            (|let [expected* (beta-reduce (->> e!env
                                               (&/Cons$ $arg)
                                               (&/Cons$ expected))
                                          e!def)]
              (check* class-loader fixpoints invariant?? expected* actual))))

        [_ (&/$ExQ a!env a!def)]
        (|do [$arg existential]
          (|let [actual* (beta-reduce (->> a!env
                                           (&/Cons$ $arg)
                                           (&/Cons$ expected))
                                      a!def)]
            (check* class-loader fixpoints invariant?? expected actual*)))

        [(&/$DataT e!data) (&/$DataT a!data)]
        (&&host/check-host-types (partial check* class-loader fixpoints true)
                                 check-error
                                 fixpoints
                                 existential
                                 class-loader
                                 invariant??
                                 e!data
                                 a!data)

        [(&/$VoidT) (&/$VoidT)]
        (return (&/T [fixpoints nil]))
        
        [(&/$UnitT) (&/$UnitT)]
        (return (&/T [fixpoints nil]))

        [(&/$LambdaT eI eO) (&/$LambdaT aI aO)]
        (|do [[fixpoints* _] (check* class-loader fixpoints invariant?? aI eI)]
          (check* class-loader fixpoints* invariant?? eO aO))

        [(&/$ProdT eL eR) (&/$ProdT aL aR)]
        (|do [[fixpoints* _] (check* class-loader fixpoints invariant?? eL aL)]
          (check* class-loader fixpoints* invariant?? eR aR))

        [(&/$SumT eL eR) (&/$SumT aL aR)]
        (|do [[fixpoints* _] (check* class-loader fixpoints invariant?? eL aL)]
          (check* class-loader fixpoints* invariant?? eR aR))

        [(&/$ExT e!id) (&/$ExT a!id)]
        (if (.equals ^Object e!id a!id)
          (return (&/T [fixpoints nil]))
          (check-error "" expected actual))

        [(&/$NamedT ?ename ?etype) _]
        (check* class-loader fixpoints invariant?? ?etype actual)

        [_ (&/$NamedT ?aname ?atype)]
        (check* class-loader fixpoints invariant?? expected ?atype)

        [_ _]
        (fail ""))
      (fn [err]
        (check-error err expected actual)))))

(defn check [expected actual]
  (|do [class-loader &/loader
        _ (check* class-loader init-fixpoints false expected actual)]
    (return nil)))

(defn actual-type [type]
  "(-> Type (Lux Type))"
  (|case type
    (&/$AppT ?all ?param)
    (|do [type* (apply-type ?all ?param)]
      (actual-type type*))

    (&/$VarT id)
    (|do [=type (deref id)]
      (actual-type =type))

    (&/$NamedT ?name ?type)
    (actual-type ?type)
    
    _
    (return type)
    ))

(defn type-name [type]
  "(-> Type (Lux Ident))"
  (|case type
    (&/$NamedT name _)
    (return name)
    
    _
    (fail (str "[Type Error] Type is not named: " (show-type type)))
    ))

(defn unknown? [type]
  "(-> Type (Lux Bool))"
  (|case type
    (&/$VarT id)
    (|do [? (bound? id)]
      (return (not ?)))

    _
    (return false)))

(defn resolve-type [type]
  "(-> Type (Lux Type))"
  (|case type
    (&/$VarT id)
    (|do [? (bound? id)]
      (if ?
        (deref id)
        (return type)))

    _
    (return type)))

(defn tuple-types-for [size-members type]
  "(-> Int Type (Maybe (List Type)))"
  (|let [?member-types (flatten-prod type)
         size-types (&/|length ?member-types)]
    (if (not (>= size-types size-members))
      &/None$
      (|let [?member-types* (if (= size-types size-members)
                              ?member-types
                              (&/|++ (&/|take (dec size-members) ?member-types)
                                     (&/|list (|case (->> (&/|drop (dec size-members) ?member-types) (&/|reverse))
                                                (&/$Cons last prevs)
                                                (&/fold (fn [right left] (Prod$ left right))
                                                        last prevs)))))]
        (&/Some$ ?member-types*)))))
