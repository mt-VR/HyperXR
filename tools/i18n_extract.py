"""Collects the Portuguese texts shown by HyperMagic (Kotlin string literals) into
app/src/main/assets/i18n/keys.json, the list the translations (en.json, es.json, ru.json) follow.

Templates become keys with numbered holes: "Página ${page + 1} de $pages" -> "Página {0} de {1}".
The app translates text when it is drawn (com.hyperxr.i18n.I18n), so logic keeps the Portuguese.
Run: python3 tools/i18n_extract.py
"""
import json
import os
import re
import sys

ROOTS = ['app/src/main/java', 'core/src/main/kotlin']
OUT = 'app/src/main/assets/i18n/keys.json'

LIT = re.compile(r'(?<![\w$])"((?:[^"\\\n]|\\.)*)"')
PT_MARK = re.compile(r'[ãõçáéíóúâêôàÃÕÇÁÉÍÓÚÂÊÔ]')
PT_WORDS = set('''você voce para com não nao uma um os as do da dos das de no na nos nas em seu sua seus suas abrir abre jogar jogue toque toca mãos mao mão aqui sem mais baixar baixe ligado desligado
pegue segure solte feche abra atire faça use entre saia mostrar mostra esconder trocar escolha pesquisar procure aguarde carregando instalado instalar jogo jogos loja pessoas amigos mensagens
ligar chamada tela ajustes configurações configuracoes biblioteca ambiente ambientes janela janelas teclado voz voltar início inicio sair fechar minimizar quando onde como que se ou e o a é está estão
pelo pela pelos pelas ao aos às cada todo toda todos todas nada algo ainda já depois antes também tambem muito pouco grande pequeno novo nova novos novas primeiro primeira último última
recentralizar câmera camera foto vídeo video vídeos som volume brilho controle controles celular modelo rastreamento passthrough limite área realidade mista modo ponto pontos nível vida tempo
segundos minutos horas dias hoje ontem amanhã vez vezes acertou errou perdeu ganhou venceu fim'''.split())
EN_WORDS = set('''the of and to is are was were failed failure error on off with without from for this that not cannot can't could would should loaded loading using use used run running
start started stop stopped null true false init bytes frames frame width height texture shader buffer thread exception invalid unknown missing found file files device devices'''.split())


def holes(lit):
    """Kotlin template -> key with {0}, {1}...; None when it is only a template."""
    out = []
    i = 0
    n = 0
    while i < len(lit):
        c = lit[i]
        if c == '\\' and i + 1 < len(lit):
            out.append({'n': '\n', 't': '\t', '"': '"', '\\': '\\', '$': '$'}.get(lit[i + 1], lit[i + 1]))
            i += 2
            continue
        if c == '$' and i + 1 < len(lit) and lit[i + 1] == '{':
            depth = 1
            j = i + 2
            while j < len(lit) and depth:
                if lit[j] == '{':
                    depth += 1
                elif lit[j] == '}':
                    depth -= 1
                j += 1
            out.append('{%d}' % n)
            n += 1
            i = j
            continue
        if c == '$' and i + 1 < len(lit) and (lit[i + 1].isalpha() or lit[i + 1] == '_'):
            j = i + 1
            while j < len(lit) and (lit[j].isalnum() or lit[j] == '_'):
                j += 1
            out.append('{%d}' % n)
            n += 1
            i = j
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def wanted(key, line):
    text = re.sub(r'\{\d+\}', ' ', key).strip()
    if len(text) < 2 or not re.search(r'[A-Za-zÀ-ú]', text):
        return False
    if '://' in key or re.fullmatch(r'[\w./:-]+\.(png|jpg|json|so|apk|tflite|task|bin|onnx|kt|zip|glb|gltf|txt|html|js|css|svg|mp4|ogg|wav)', text):
        return False
    if re.search(r'Diagnostics\.log|Log\.[dewiv]\(|println\(|logcat|IllegalStateException|error\("|check\(|require\(', line):
        return False
    if re.fullmatch(r'[A-Z0-9]+(-[A-Z0-9]+)+', text):
        return False   # wire-protocol tags
    if ('_' in text and ' ' not in text) or re.search(r'[\[\]^\\|]|HTTP/|%[0-9.]*[dfs]', text):
        return False
    if re.fullmatch(r'[A-Za-z0-9.+\- ]*[0-9][A-Za-z0-9.+\- ]*', text) and not PT_MARK.search(text):
        return False   # model names, versions
    words = re.findall(r'[A-Za-zÀ-ú]+', text.lower())
    if not words:
        return False
    en = sum(w in EN_WORDS for w in words); pt = sum(w in PT_WORDS for w in words)
    if en > pt and not PT_MARK.search(text):
        return False
    if PT_MARK.search(text) or any(w in PT_WORDS for w in words):
        return True
    # capitalised words and phrases (names on buttons and titles): "Loja", "Pessoas", "Ritmo Neon"
    if re.fullmatch(r"[A-ZÀ-Ú][\wÀ-ú'!?.,:;()\- ]*", text) and not re.fullmatch(r'[A-Z0-9_]+', text) and not re.search(r'[a-z][A-Z]', text):
        return True
    # lower-case phrases of several words
    return len(words) >= 3 and ' ' in text


def main():
    keys = {}
    for root in ROOTS:
        for dp, _, fs in os.walk(root):
            for f in fs:
                if not f.endswith('.kt'):
                    continue
                path = os.path.join(dp, f)
                for line in open(path, encoding='utf-8'):
                    s = line.strip()
                    if s.startswith('//') or s.startswith('*') or s.startswith('/*') or s.startswith('import ') or s.startswith('package '):
                        continue
                    # the code part of the line, without a trailing comment
                    for m in LIT.finditer(line):
                        key = holes(m.group(1))
                        if wanted(key, line):
                            keys.setdefault(key, path)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    out = sorted(keys)
    json.dump(out, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
    print(len(out), 'keys ->', OUT)


if __name__ == '__main__':
    main()
