package com.lilac.anime.data.subtitle.translation.localai

import android.content.Context
import android.os.Build

/** Runtime selection is independent of model family, architecture and quantization. */
object RuntimeRegistry {
    const val AUTO = "auto"

    data class Candidate(
        val id: String,
        val name: String,
        val version: String,
        val kind: Kind,
        val pack: RuntimePack? = null
    )

    enum class Kind { NATIVE_PACK }

    fun isQualcommDevice(): Boolean {
        val hardware = Build.HARDWARE.orEmpty().lowercase()
        val manufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER.orEmpty().lowercase() else ""
        return manufacturer.contains("qualcomm") || manufacturer.contains("qcom") ||
            hardware.contains("qcom") || hardware.contains("qualcomm")
    }

    /** Lower-case SoC identifiers of this device, used to match `socVendors` tokens. */
    private fun socIdentity(): String = buildList {
        add(Build.HARDWARE.orEmpty())
        add(Build.BOARD.orEmpty())
        if (Build.VERSION.SDK_INT >= 31) {
            add(Build.SOC_MANUFACTURER.orEmpty())
            add(Build.SOC_MODEL.orEmpty())
        }
    }.joinToString(" ").lowercase()

    /** 2 = built for this SoC, 1 = SoC-agnostic, 0 = built for another SoC vendor. */
    private fun socScore(pack: RuntimePack, identity: String): Int {
        val vendors = pack.effectiveSocVendors
        if (vendors.isEmpty()) return 1
        return if (vendors.any { identity.contains(it) }) 2 else 0
    }

    fun all(context: Context): List<Candidate> = LocalAiRuntimeManager.listInstalled(context).map {
        Candidate(it.id, it.name, it.version, Kind.NATIVE_PACK, it)
    }

    fun compatible(context: Context, model: LocalAiModel): List<Candidate> = all(context).filter { candidate ->
        val pack = candidate.pack ?: return@filter false
        val arch = model.architecture.orEmpty()
        val archOk = pack.supportedArchitectures.isEmpty() ||
            pack.supportedArchitectures.any { it.equals(arch, true) }
        val quantParts = model.quantization.orEmpty()
            .split(',')
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
        val quantOk = pack.supportedQuantizations.isEmpty() ||
            quantParts.all { pack.supportedQuantizations.contains(it) }
        archOk && quantOk
    }

    fun preferenceKey(modelId: String): String =
        "pref_translation_runtime_${modelId.replace(Regex("[^A-Za-z0-9._-]"), "_")}"

    fun selectedId(context: Context, model: LocalAiModel): String =
        context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
            .getString(preferenceKey(model.id), AUTO) ?: AUTO

    fun setSelectedId(context: Context, model: LocalAiModel, runtimeId: String) {
        context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
            .edit().putString(preferenceKey(model.id), runtimeId).apply()
    }

    fun select(context: Context, model: LocalAiModel): Candidate? {
        val candidates = compatible(context, model)
        if (candidates.isEmpty()) return null
        val preferred = selectedId(context, model)
        if (preferred != AUTO) candidates.firstOrNull { it.id == preferred }?.let { return it }
        // Auto: a Snapdragon (Hexagon/OpenCL) pack only reaches the CPU on Exynos,
        // so prefer packs built for this SoC, then SoC-agnostic ones (e.g. Vulkan).
        val identity = socIdentity()
        return candidates.maxByOrNull { candidate -> candidate.pack?.let { socScore(it, identity) } ?: 0 }
    }
}
