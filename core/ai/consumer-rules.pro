# Consumer R8/ProGuard rules for :core:ai — the module that owns the Koog dependency.
# AGP merges this file into the R8 config of EVERY Android app variant whose classpath
# includes :core:ai (Orpheus androidApp, djapp's ai flavor; djapp og never pulls it, so og
# stays maximally shrunk). Koog 1.0.0 ships ZERO consumer rules of its own (their samples
# set isMinifyEnabled=false), so until JetBrains/koog#1068 is fixed upstream, this file is
# the single source of truth for keeping Koog's kotlin-reflect machinery alive under R8.
# History below is real: each block was root-caused from a shipped DEX/mapping.txt.

# --- BUG 1 (root-caused 2026-07-01 from mapping.txt): Function-type rename breaks metadata ---
# Koog builds its agent graph from delegated DSL builder functions (e.g.
#   val node by nodeLLMRequestStreaming()
# in OrpheusAgentConfig) that kotlin-reflect resolves at runtime by reading @kotlin.Metadata.
# R8 renamed kotlin.jvm.functions.Function2 to a synthetic name; a -keep on the koog method
# does NOT pin its PARAMETER types, so the live method descriptor took the renamed Function2
# while the JVM-signature string frozen inside @kotlin.Metadata still named Function2.
# kotlin-reflect reads the frozen metadata, can't map it to the renamed method, and throws
# "nodeLLMRequestStreaming ... not resolved in file class AIAgentNodesKt: no members found",
# killing the whole agent run (empty Activity/Thinking feeds, generation stuck). Pinning the
# NAMES keeps descriptor and metadata in lockstep; -keepnames still allows shrinking.
-keepnames class kotlin.jvm.functions.** { *; }
-keepnames class kotlin.Function** { *; }

# Freeze koog's own builder facades + metadata so their names/metadata stay in lockstep
# with the reflection that reads them.
-keep class ai.koog.** { *; }
-keepclassmembers class ai.koog.** { *; }
-keep class kotlin.Metadata { *; }
-keepattributes InnerClasses,Signature,RuntimeVisible*Annotations,EnclosingMethod
-dontwarn ai.koog.**
-dontwarn kotlin.reflect.jvm.internal.**

# --- BUG 2 (root-caused 2026-07-12 from the aiDebugRelease DEX; JetBrains/koog#1068) ---
# kotlin-reflect's typeOf<Outer<Inner>>() validates the classifier's DECLARED type-parameter
# count against the type arguments baked into the call site's bytecode. That declaration lives
# in the classifier's own Signature attribute / @kotlin.Metadata, and R8 strips BOTH from any
# class matched by NO keep rule, in compat AND full mode alike (this is why
# android.enableR8.fullMode=false never actually covered this crash; the flag was removed
# 2026-07-12). DEX evidence from the broken build: Flow was renamed to zv1 with no Signature
# and no Metadata, while keep-matched classes kept both, rewritten consistently.
#
# Koog funnels every runtime typeOf through ai.koog.serialization.typeToken<T>() (inlined into
# each reified DSL builder). The streaming node builders (nodeLLMRequestStreaming /
# nodeLLMSendToolResultsStreaming) are the ones whose node I/O is a parameterized type,
# typeOf<Flow<StreamFrame>>(), so a stripped Flow declares 0 type parameters and the agent
# dies at graph build with:
#   IllegalArgumentException: Class declares 0 type parameters, but 1 were provided
# kotlin.Result is the other non-builtin generic classifier Koog passes to typeOf (structured
# nodes, nodeLLMRequestStructured / requestLLMStructured). kotlin.collections.List needs no
# rule: kotlin-reflect resolves stdlib collections from its built-in descriptors, not from
# class-file metadata. Hard -keep (not -keepnames) because full mode retains attributes only
# on keep-matched items and these two types are too small to be worth shaving; implementations
# of Flow remain fully shrinkable either way. Verified in the rebuilt DEX: Flow keeps
# name + Signature <T> + Metadata under full mode, and the full agent loop (streaming +
# tool calls + Vibe JSON decode) ran clean on-device.
-keep interface kotlinx.coroutines.flow.Flow { *; }
-keep class kotlin.Result { *; }

# Keeping ai.koog.** makes R8 trace into Koog's bundled OpenTelemetry integration, which
# references AutoValue (a compile-time-only annotation processor, never on the runtime
# classpath) and a few optional/incubator OpenTelemetry metrics APIs this OpenTelemetry
# version doesn't ship. Neither is reachable at runtime — Koog's tracing isn't configured
# with any exporter in these apps — so warning suppression is correct, not a keep rule.
-dontwarn com.google.auto.value.**
-dontwarn io.opentelemetry.api.incubator.**
# Jackson (transitive via Koog) references java.beans.* which does not exist on Android.
# These are optional Java SE reflection hooks Jackson probes for and skips at runtime; the
# standard Android suppression.
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
