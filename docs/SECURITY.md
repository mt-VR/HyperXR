# Modelo de segurança do SDK

Objetivo: scripts Lua de terceiros nunca têm acesso irrestrito ao processo Android, a menos que o
usuário conceda `unsafe` explicitamente.

## Camadas

1. **Inspeção antes da instalação** (`HyperxPackage.inspect`): manifesto estrito, SHA-256 de todos os
   arquivos, caminhos seguros, limites contra zip bomb. Nada é executado.
2. **Ambiente restrito** (`bootstrap.lua`): o script recebe só uma cópia controlada de `string`,
   `table`, `math`, `bit`, `coroutine`, `os.clock/time/date`, e as APIs `hx.*` permitidas.
   - Ausentes: `io`, `os.execute/exit/getenv/remove`, `debug`, `ffi`, `jit`, `package`, `dofile`,
     `loadfile`, `string.dump`, `collectgarbage` (exceto `count`/`step`).
   - `load`/`loadstring` aceitam só texto (bytecode é rejeitado) e sempre recebem o ambiente do sandbox.
   - `require` só carrega `.lua` do próprio pacote, com nomes validados.
   - A metatable compartilhada de strings fica inacessível (`__metatable = false`).
   - Tabelas `hx.*` são somente leitura.
   - `string.rep` limitado a 16 MB.
3. **Verificação no host**: cada chamada (`fs_*`, `net_request`, `audio_*`, `camera_texture`) confere a
   permissão de novo no Kotlin; arquivos só na pasta do pacote (caminho canônico), cota de 16 MB; rede
   só HTTPS para `allowedHosts`, sem seguir redirecionamentos, resposta limitada a 2 MB.
4. **Orçamento de execução**: hook de contagem de instruções por callback (20 M; ×20 no carregamento)
   e limite de memória (192 MB). Depois de estourar, o hook passa a disparar a cada instrução, então
   um `pcall` do script não consegue mantê-lo vivo.
5. **Isolamento de processo + watchdog**: pacotes rodam no processo `:game`. Se um quadro Lua passar de
   4 s, o watchdog grava o motivo e encerra só esse processo; o launcher continua.

## Limitação conhecida: JIT e hooks

O LuaJIT **não chama hooks dentro de código compilado pelo JIT**. Verificado neste projeto: um
`while true do end` com JIT ligado ignora o hook. Por isso:

- Pacotes de terceiros rodam por padrão em **modo estrito** (JIT desligado): o orçamento é sempre
  aplicado. O usuário pode desligar o modo estrito por pacote (mais desempenho, menos garantia).
- Pacotes embutidos (escritos e revisados no repositório) rodam com JIT.
- Em qualquer modo o watchdog de processo é a barreira final contra laços infinitos.

## `unsafe`

Só pode ser concedida no app 2D, digitando `UNSAFE`, para um pacote que a declarou e passou na
verificação de integridade (`PermissionPolicy.grant`). Com ela o pacote recebe `ffi`, `jit`, `debug`,
`io`, `os` completos e bytecode, e passa a poder fazer tudo que o processo `:game` pode fazer.

## Testes

`sdk/tests/run_tests.lua` verifica as fugas acima, orçamento, `pcall` contra o kill, memória e
permissões; `HyperxPackageTest` cobre adulteração, path traversal e permissões; `LuaBridgeHostTest`
roda a ponte JNI real e confirma que o sandbox não alcança a tabela nativa.
