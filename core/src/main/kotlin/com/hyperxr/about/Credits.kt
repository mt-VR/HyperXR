package com.hyperxr.about

/**
 * Everything HyperMagic is built on that it did not write itself: who made it, its licence and
 * what it does here. Shown in the app (Início > Créditos e licenças); the README's "Third-party
 * licences" lists the same.
 */
object Credits {
    class Entry(val name: String, val author: String, val license: String, val use: String)

    val partners = listOf(
        Entry("MagicXR", "Samrat Beketov", "GPL-3.0 (cedido ao HyperMagic)", "trava da pinça; logo feita em parceria"),
        Entry("PhoneXR", "Samrat Beketov", "trazido ao HyperMagic pelo próprio autor", "inclinação e passos pelo acelerômetro, fotos 3D"),
    )

    val thirdParty = listOf(
        Entry("LuaJIT", "Mike Pall", "MIT", "scripts dos pacotes .hyperx"),
        Entry("OpenXR loader e headers", "Khronos Group", "Apache-2.0 / MIT", "runtime OpenXR e jogos OpenXR"),
        Entry("ARCore, MediaPipe, LiteRT, AndroidX, Compose", "Google", "Apache-2.0", "6DoF, mãos, IA no celular, interface"),
        Entry("RTMDet-nano e RTMPose-m", "OpenMMLab", "Apache-2.0", "rastreamento das mãos"),
        Entry("Real-ESRGAN general x4v3", "Xintao Wang et al. (export Qualcomm AI Hub)", "BSD-3-Clause", "imagem da câmera melhorada pela NPU"),
        Entry("Resonance Audio", "Google", "Apache-2.0", "som 3D (HRTF) em tudo o que toca no VR"),
        Entry("Eigen, PFFFT, base HRTF SADIE", "Eigen, Julien Pommier, Univ. de York", "MPL-2.0, BSD, Apache-2.0", "partes do Resonance Audio"),
        Entry("Depth Anything V2 Small", "Lihe Yang et al. (export Qualcomm AI Hub)", "Apache-2.0", "profundidade por IA, fotos 3D"),
        Entry("GorillaLocomotion", "Another Axiom", "MIT", "movimento dos jogos de gorila"),
        Entry("Open Saber", "leandrodreamer (Beep Saber de NeoSpark314)", "MIT", "jogo na Loja, compilado do código-fonte"),
        Entry("Pojlib / QuestCraft", "QuestCraftPlusPlus", "LGPL-3.0", "Minecraft Java em VR"),
        Entry("ViveMonkeCraft", "laggyboi20-jpg", "MIT", "perfil GorillaCraft"),
        Entry("GeckoView", "Mozilla", "MPL-2.0", "navegador"),
        Entry("Ícones do Wolvic", "Igalia", "MPL-2.0", "barra do navegador"),
        Entry("Shizuku API", "RikkaApps", "Apache-2.0", "apps Android em janelas"),
        Entry("Qwen 2.5", "Alibaba", "Apache-2.0", "assistente de IA (baixado pelo usuário)"),
        Entry("Núcleos libretro", "QuickNES, Gambatte, mGBA, DOSBox Pure", "GPL-2.0 / MPL-2.0", "emuladores"),
        Entry("Kenney kits", "Kenney", "CC0", "modelos 3D dos jogos"),
        Entry("Poly Haven", "Poly Haven", "CC0", "ambientes 360"),
        Entry("Material Symbols", "Google", "Apache-2.0", "ícones dos painéis"),
        Entry("ONNX Runtime, OpenCV, NumPy", "Microsoft, OpenCV, NumPy", "MIT, Apache-2.0, BSD-3", "HybridMagic no PC"),
    )
}
