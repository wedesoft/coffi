(ns coffi.ffi
  (:refer-clojure :exclude [defstruct])
  (:require
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s])
  (:import
   (java.lang.invoke
    VarHandle
    MethodHandle
    MethodType)
   (jdk.incubator.foreign
    CLinker
    FunctionDescriptor
    GroupLayout
    MemoryAccess
    MemoryAddress
    MemoryHandles
    MemoryLayout
    MemoryLayout$PathElement
    MemoryLayouts
    MemorySegment
    ResourceScope
    SegmentAllocator)))

(defn alloc
  "Allocates `size` bytes."
  ([size] (alloc size (ResourceScope/newImplicitScope)))
  ([size scope] (MemorySegment/allocateNative ^long size ^ResourceScope scope)))

(defn stack-scope
  "Constructs a new scope for use only in this thread.

  The memory allocated within this scope is cheap to allocate, like a native
  stack."
  []
  (ResourceScope/newConfinedScope))

(defn shared-scope
  "Constructs a new shared scope.

  This scope can be shared across threads and memory allocated in it will only
  be cleaned up once every thread accessing the scope closes it."
  []
  (ResourceScope/newSharedScope))

(defmacro with-acquired
  "Acquires a `scope` to ensure it will not be released until the `body` completes.

  This is only necessary to do on shared scopes, however if you are operating on
  an arbitrary passed scope, it is best practice to wrap code that interacts
  with it wrapped in this."
  [scope & body]
  `(let [scope# ~scope
         handle# (.acquire ^ResourceScope scope#)]
     (try ~@body
          (finally (.release ^ResourceScope scope# handle#)))))

(defn address-of
  "Gets the address of a given segment.

  This value can be used as an argument to functions which take a pointer."
  [segment]
  (.address ^MemorySegment segment))

(defn slice-global
  "Gets a slice of the global address space.

  Because this fetches from the global segment, it has no associated scope, and
  therefore the reference created here cannot prevent the value from being
  freed. Be careful to ensure that you are not retaining an object incorrectly."
  [address size]
  (.asSlice (MemorySegment/globalNativeSegment)
            ^MemoryAddress address ^long size))

(defn slice
  "Get a slice over the `segment` with the given `offset`."
  ([segment offset]
   (.asSlice ^MemorySegment segment ^long offset))
  ([segment offset size]
   (.asSlice ^MemorySegment segment ^long offset ^long size)))

(defn slice-into
  "Get a slice over the `segment` starting at the `address`."
  ([address segment]
   (.asSlice ^MemorySegment segment ^MemoryAddress address))
  ([address segment size]
   (.asSlice ^MemorySegment segment ^MemoryAddress address ^long size)))

(defn with-offset
  "Get a new address `offset` from the old `address`."
  [address offset]
  (.addOffset ^MemoryAddress address ^long offset))

(defn as-segment
  "Dereferences an `address` into a memory segment associated with the `scope`.

  If `cleanup` is provided, it is a 0-arity function run when the scope is
  closed. This can be used to register a free method for the memory, or do other
  cleanup in a way that doesn't require modifying the code at the point of
  freeing, and allows shared or garbage collected resources to be freed
  correctly."
  ([address size scope]
   (.asSegment ^MemoryAddress address size scope))
  ([address size scope cleanup]
   (.asSegment ^MemoryAddress address size cleanup scope)))

(defn add-close-action!
  "Adds a 0-arity function to be run when the `scope` closes."
  [scope action]
  (.addCloseAction ^ResourceScope scope action))

#_(defn seq-of
  "Constructs a lazy sequence of `type` elements deserialized from `segment`."
  [type segment]
  (let [size (size-of type)]
    (letfn [(rec [segment]
              (lazy-seq
               (when (>= (.byteSize ^MemorySegment segment) size)
                 (cons (deserialize-from type segment)
                       (rec (slice segment size))))))]
      (rec segment))))

(def primitive-types
  "A set of keywords representing all the primitive types which may be passed to
  native functions."
  #{::byte ::short ::int ::long ::long-long
    ::char
    ::float ::double
    ::pointer})

(def c-prim-layout
  "Map of primitive type names to the [[CLinker]] types for a method handle."
  {::byte CLinker/C_CHAR
   ::short CLinker/C_SHORT
   ::int CLinker/C_INT
   ::long CLinker/C_LONG
   ::long-long CLinker/C_LONG_LONG
   ::char CLinker/C_CHAR
   ::float CLinker/C_FLOAT
   ::double CLinker/C_DOUBLE
   ::pointer CLinker/C_POINTER})

(defmulti c-layout
  "Gets the layout object for a given `type`.

  If a type is primitive it will return the appropriate primitive
  layout (see [[c-prim-layout]]).

  Otherwise, it should return a [[GroupLayout]] for the given type."
  (fn [type] type))

(defmethod c-layout :default
  [type]
  (c-prim-layout type))

(defmulti primitive-type
  "Gets the primitive type that is used to pass as an argument for the `type`.

  This is for objects which are passed to native functions as primitive types,
  but which need additional logic to be performed during serialization and
  deserialization.

  Returns nil for any type which does not have a primitive representation."
  (fn [type] type))

(defmethod primitive-type :default
  [type]
  (primitive-types type))

(defmethod primitive-type ::c-string
  [_type]
  ::pointer)

(def java-prim-layout
  "Map of primitive type names to the Java types for a method handle."
  {::byte Byte/TYPE
   ::short Short/TYPE
   ::int Integer/TYPE
   ::long Long/TYPE
   ::long-long Long/TYPE
   ::char Byte/TYPE
   ::float Float/TYPE
   ::double Double/TYPE
   ::pointer MemoryAddress
   ::void Void/TYPE})

(defmulti java-layout
  "Gets the Java class to an argument of this type for a method handle."
  (fn [type] type))

(defmethod java-layout :default
  [type]
  (java-prim-layout type MemorySegment))

(defn size-of
  "The size in bytes of the given `type`."
  [type]
  (let [layout ^MemoryLayout (c-layout type)]
    (.byteSize
     (cond-> layout
       (qualified-keyword? layout) ^MemoryLayout c-layout))))

(defn alloc-instance
  "Allocates a memory segment for the given `type`."
  ([type] (alloc-instance type (ResourceScope/newImplicitScope)))
  ([type scope] (MemorySegment/allocateNative ^long (size-of type) ^ResourceScope scope)))

(defmulti serialize*
  "Constructs a serialized version of the `obj` and returns it.

  Any new allocations made during the serialization should be tied to the given
  `scope`, except in extenuating circumstances.

  This method should only be implemented for types that serialize to primitives."
  (fn
    #_{:clj-kondo/ignore [:unused-binding]}
    [obj type scope]))

(defmethod serialize* :default
  [obj type _scope]
  (if (primitive-type type)
    obj
    (throw (ex-info "Attempted to serialize a non-primitive type with primitive methods"
                    {:type type
                     :object obj}))))

(defmulti serialize-into
  "Writes a serialized version of the `obj` to the given `segment`.

  Any new allocations made during the serialization should be tied to the given
  `scope`, except in extenuating circumstances.

  This method should be implemented for any type which does not
  override [[c-layout]].

  For any other type, this will serialize it as [[serialize*]] before writing
  the result value into the `segment`."
  (fn
    #_{:clj-kondo/ignore [:unused-binding]}
    [obj type segment scope]
    type))

(defmethod serialize* ::c-string
  [obj _type scope]
  (address-of (CLinker/toCString (str obj) ^ResourceScope scope)))

(defmethod serialize-into :default
  [obj type segment scope]
  (let [new-type (c-layout type)]
    (if (qualified-keyword? new-type)
      (serialize-into (serialize* obj type scope) new-type segment scope)
      (throw (ex-info "Attempted to serialize an object to a type that has not been overriden."
                      {:type type
                       :object obj})))))

(defmethod serialize-into ::byte
  [obj _type segment _scope]
  (MemoryAccess/setByte segment (byte obj)))

(defmethod serialize-into ::short
  [obj _type segment _scope]
  (MemoryAccess/setShort segment (short obj)))

(defmethod serialize-into ::int
  [obj _type segment _scope]
  (MemoryAccess/setInt segment (int obj)))

(defmethod serialize-into ::long
  [obj _type segment _scope]
  (MemoryAccess/setLong segment (long obj)))

(defmethod serialize-into ::long-long
  [obj _type segment _scope]
  (MemoryAccess/setLong segment (long obj)))

(defmethod serialize-into ::char
  [obj _type segment _scope]
  (MemoryAccess/setChar segment (char obj)))

(defmethod serialize-into ::float
  [obj _type segment _scope]
  (MemoryAccess/setFloat segment (float obj)))

(defmethod serialize-into ::double
  [obj _type segment _scope]
  (MemoryAccess/setDouble segment (double obj)))

(defmethod serialize-into ::pointer
  [obj _type segment _scope]
  (MemoryAccess/setAddress segment obj))

(defmulti deserialize-from
  "Deserializes the given segment into a Clojure data structure."
  (fn
    #_{:clj-kondo/ignore [:unused-binding]}
    [segment type]
    type))

(defmethod deserialize-from ::byte
  [segment _type]
  (MemoryAccess/getByte segment))

(defmethod deserialize-from ::short
  [segment _type]
  (MemoryAccess/getShort segment))

(defmethod deserialize-from ::int
  [segment _type]
  (MemoryAccess/getInt segment))

(defmethod deserialize-from ::long
  [segment _type]
  (MemoryAccess/getLong segment))

(defmethod deserialize-from ::long-long
  [segment _type]
  (MemoryAccess/getLong segment))

(defmethod deserialize-from ::char
  [segment _type]
  (MemoryAccess/getChar segment))

(defmethod deserialize-from ::float
  [segment _type]
  (MemoryAccess/getFloat segment))

(defmethod deserialize-from ::double
  [segment _type]
  (MemoryAccess/getDouble segment))

(defmethod deserialize-from ::pointer
  [segment _type]
  (MemoryAccess/getAddress segment))

(defmulti deserialize*
  "Deserializes a primitive object into a Clojure data structure.

  This is intended for use with types that are returned as a primitive but which
  need additional processing before they can be returned."
  (fn
    #_{:clj-kondo/ignore [:unused-binding]}
    [obj type]
    type))

(defmethod deserialize* :default
  [obj _type]
  obj)

(defmethod deserialize-from ::c-string
  [segment type]
  (-> segment
      (deserialize-from ::pointer)
      (deserialize* type)))

(defmethod deserialize* ::c-string
  [obj _type]
  (CLinker/toJavaString obj))

(defn serialize
  [])

(defn deserialize
  "Deserializes an arbitrary type regardless of if it is primitive.

  For types which have a primitive representation, this deserializes the
  primitive representation. For types which do not, this deserializes out of
  a [[MemorySegment]]."
  [obj type]
  ((if (primitive-type type)
     deserialize*
     deserialize-from)
   obj type))

(defn serialize
  "Serializes the `obj` into a newly-allocated [[MemorySegment]]."
  ([obj type] (serialize obj type (ResourceScope/newImplicitScope)))
  ([obj type scope] (serialize-into obj type (alloc-instance type scope) scope)))

(defn load-system-library
  "Loads the library named `libname` from the system's load path."
  [libname]
  (System/loadLibrary (name ~libname)))

(defn load-library
  "Loads the library at `path`."
  [path]
  (System/load (.getAbsolutePath (io/file path))))

(defn- find-symbol
  "Gets the [[MemoryAddress]] of a symbol from the loaded libraries."
  [sym]
  (.. (CLinker/systemLookup) (lookup sym) (get)))

(defn- method-type
  "Gets the [[MethodType]] for a set of `args` and `ret` types."
  ([args] (method-type args ::void))
  ([args ret]
   (MethodType/methodType
    ^Class ret
    ^"[Ljava.lang.Class;" (into-array Class (map java-layout args)))))

(defn- function-descriptor
  "Gets the [[FunctionDescriptor]] for a set of `args` and `ret` types."
  ([args] (function-descriptor args ::void))
  ([args ret]
   (let [args-arr (into-array MemoryLayout (map c-layout args))]
     (if-not (identical? ret ::void)
       (FunctionDescriptor/of
        (c-layout ret)
        args-arr)
       (FunctionDescriptor/ofVoid
        args-arr)))))

(defn- downcall-handle
  "Gets the [[MethodHandle]] for the function at the `address`."
  [address method-type function-descriptor]
  (.downcallHandle (CLinker/getInstance) address method-type function-descriptor))

(s/def ::defcfn-args
  (s/cat :name simple-symbol?
         :doc (s/? string?)
         :symbol (s/nonconforming
                  (s/or :string string?
                        :symbol simple-symbol?))
         :native-arglist (s/coll-of qualified-keyword? :kind vector?)
         :return-type qualified-keyword?
         :fn-tail (s/?
                   (s/cat :arglist (s/coll-of simple-symbol? :kind vector?)
                          :body (s/* any?)))))

(defmacro defcfn
  {:arglists '([name docstring? symbol arg-types ret-type arglist & body])}
  [& args]
  (let [args (s/conform ::defcfn-args args)
        scope (gensym "scope")
        arg-syms (repeatedly (count (:native-arglist args)) #(gensym "arg"))]
    `(let [args-types# ~(:native-arglist args)
           ret-type# ~(:return-type args)
           downcall# (downcall-handle
                      (find-symbol ~(:symbol args))
                      (method-type args-types# ret-type#)
                      (function-descriptor args-types# ret-type#))
           ~(:name args) (fn [& args#]
                           (with-open [~scope (stack-scope)]
                             (let [[~@arg-syms] (map #(serialize ))]
                               (.invoke downcall# ~@arg-syms))))
           fun# ~(if (:fn-tail args)
                   `(fn ~(-> args :fn-tail :arglist)
                      ~@(-> args :fn-tail :body))
                   (:name args))]
       (def
         ~(vary-meta (:name args)
                     update :arglists
                     (fn [old-list]
                       (or old-list
                           (list
                            (or (-> args :fn-tail :arglist)
                                (mapv (comp symbol name)
                                      (:native-arglist args)))))))
         ~@(list (:doc args))
         fun#))))

(comment

  (let [args-types [::c-string]
        ret-type ::int
        downcall (downcall-handle
                  (find-symbol "strlen")
                  (method-type args-types ret-type)
                  (function-descriptor args-types ret-type))
        strlen (fn [str]
                 (with-open [scope (stack-scope)]
                   (let [arg1 (serialize (nth args-types 0) str scope)]
                     (deserialize* (.invoke downcall arg1) ret-type))))]
    (def
      ^{:arglists '([str])}
      strlen
      "Counts the number of bytes in a C string."
      strlen))

  )

#_:clj-kondo/ignore
(comment
  ;;; Prospective syntax for ffi

  ;; This function has no out params, and no extra marshalling work, so it has no
  ;; body
  (-> (defcfn strlen
        "Counts the number of bytes in a C String."
        "strlen" [::c-string] ::int)
      quote
      macroexpand-1)

  ;; This function has an output parameter and requires some clojure code to
  ;; translate the values from the c fn to something sensible in clojure.
  (defcfn some-func
    "Gets some output value"
    "someFunc" [::pointer] ::int
    []
    (with-open [scope (stack-scope)]
      (let [out-int (alloc-instance ::int scope)
            success? (zero? (some-func (address-of out-int)))]
        (if success?
          (deserialize-from ::int out-int)
          (throw (ex-info (getErrorString) {}))))))

  ;; This function probably wouldn't actually get wrapped, since the cost of
  ;; marshalling is greater than the speed boost of using an in-place sort. That
  ;; said, this is a nice sample of what more complex marshalling looks like.
  (defcfn qsort
    "Quicksort implementation"
    "qsort"
    [::pointer ::long ::long (fn [::pointer ::pointer] ::int)]
    ::void
    [type comparator list]
    (with-open [scope (stack-scope)]
      (let [copied-list (alloc (* (count list) (size-of type)) scope)
            _ (dorun (map #(serialize-into %1 type %2 scope) list (seq-of type copied-list)))
            comp-fn (fn [addr1 addr2]
                      (let [obj1 (deserialize-from type (slice-global addr1 (size-of type)))
                            obj2 (deserialize-from type (slice-global addr2 (size-of type)))]
                        (comparator obj1 obj2)))]
        (qsort copied-list (count list) (size-of type) comp-fn)
        (for [segment (seq-of type copied-list)]
          (deserialize-from type segment)))))
  )
