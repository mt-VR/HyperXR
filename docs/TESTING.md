# Testes

| Suíte | Comando | O que cobre |
|---|---|---|
| Core (JVM, 35 testes) | `./gradlew :core:test` | persistência e alternância Dock/New, dock (fixar/reordenar), recentes, layout espacial; `.hyperx` (empacotar, adulteração, arquivo extra, path traversal, manifesto, não-ZIP, extração); permissões (`unsafe` só com confirmação, não declaradas recusadas); importação GLB/glTF (hierarquia, data URI, buffer externo, Draco recusado, truncado); One Euro; perda/recuperação do tracking sem salto; esqueleto implausível; gestos; fusão de profundidade (mapa e prior); Direct Touch (histerese, debounce, toque por trás); raio com captura de arrasto; botões sem repetição e liberação na desconexão; IMU + recentralização do Joy-Con; resolução de modo MR/VR; seleção de runtime; árbitro de sessão; política de licenças; parser Poly Haven; percentis de latência |
| SDK Lua (26 testes) | `luajit sdk/tests/run_tests.lua` | sandbox (globais perigosos, `load` de texto, bytecode, metatable de string, `require` local), orçamento de execução, `pcall` não salva o kill, limite de memória, erro → painel de falha, permissões; física (repouso, pilha estável, quique, raycast, porta com dobradiça, escala de tempo); UI (clique por olhar+toque, Joy-Con R, Direct Touch, lista em modo Window, tweens); os 6 pacotes rodando 1200 quadros com entrada simulada; parser da calculadora |
| Ponte JNI real no host (5 testes) | ver abaixo | `lua_bridge.cpp` + `LuaVm.kt` + `bootstrap.lua` reais: marshaling de números/strings UTF-8, buffers de draw/painel/entrada, logs, falhas reportadas, tabela nativa invisível ao sandbox |
| Lint Android | `./gradlew :app:lintDebug` | APIs acima do minSdk 26, manifesto, recursos |

## Ponte JNI no host

```bash
# 1. LuaJIT 2.1 de desktop compilado com -fPIC
git clone https://luajit.org/git/luajit.git -b v2.1 /tmp/luajit && make -C /tmp/luajit CFLAGS=-fPIC BUILDMODE=static
# 2. biblioteca de teste
tools/build_host_bridge.sh /tmp/luajit /tmp/hxbridge
# 3. testes
./gradlew :app:testDebugUnitTest -Phyperxr.hostBridge=/tmp/hxbridge
```
Sem `-Phyperxr.hostBridge` esses testes são pulados (Assume).

## O que não foi testado

Não há aparelho nem emulador neste ambiente (sem KVM). Tudo que depende de hardware — renderização
GL, câmera, ARCore, sensores, Joy-Cons, inferência LiteRT, OpenXR, WebView em textura — foi compilado
(APK gerado, lint limpo) mas **não executado**. Ver STATUS.md.
