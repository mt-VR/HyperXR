# HyperGuardian

Mantém você numa área segura em todos os apps que rodam no motor do HyperXR (launcher, Oceano,
Hand Physics Lab, apps .hyperx, emuladores...). Fica na biblioteca, em Sistema, com o ícone de
escudo.

Precisa da posição do celular pelo ARCore (6DoF). No VR, o HyperXR liga o ARCore sozinho quando
o HyperGuardian está ligado: depois de ligar, saia e entre de novo no modo VR.

## Manual

**Desenhar a área:**
1. Toque em "Desenhar área".
2. Aponte para o chão e faça a pinça. O raio vai do ombro, passa pelos dedos e marca o chão.
3. Contorne a área, andando ou apontando, e volte ao ponto de partida. Soltar a pinça perto do
   começo fecha a área (ou toque em "Concluir área").

**Limpeza do traço:** a linha é limpa sozinha:
- pontos a cada 8 cm;
- um laço desenhado sem querer, ou a sobra no final, é cortado;
- o contorno é simplificado, então uma mão tremida ainda dá paredes retas.

Menos de 1 m² não vale como área.

**Avisos:**
- Perto da borda aparece uma parede de grade azul.
- Fora da área, a câmera mostra o quarto por cima de tudo, com "Volte para a área", até você
  voltar.

**A área fica salva.** Ela vale a partir de onde o HyperXR começou (o ARCore não reconhece o
quarto entre sessões): comece no mesmo lugar, olhando para a mesma direção, ou desenhe de novo.

## Automático

O sensor de profundidade do ARCore mapeia paredes e móveis enquanto você olha em volta. É um
mapa visto de cima, com células de 5 cm. Perto de uma parede, a grade aparece nela; mais perto,
a câmera mostra o quarto com "Cuidado: parede perto".

### Proteções contra falsos avisos

- **O seu corpo:** pontos a menos de 45 cm da cabeça (na horizontal) e perto das mãos
  rastreadas são ignorados. Braços, mãos e pernas nunca viram parede.
- **Chão e teto:** só conta o que está entre 12 cm acima do chão e 30 cm acima da cabeça.
- **Ruído:** um lugar só vira obstáculo depois de vários pontos em leituras separadas.
- **Coisas que se mexem (pessoa, porta, animal):** cada raio do sensor que passa por um lugar
  até algo atrás dele prova que o lugar está vazio e apaga o obstáculo. Isso só vale onde o raio
  passa mais baixo que o topo do obstáculo: olhar por cima da cama até a parede não apaga a cama.
- **Cama, sofá, mesa (até 1,1 m):**
  - são "móveis baixos", em laranja, e avisam só bem perto;
  - nunca trazem a câmera inteira sozinhos;
  - não contam quando você já está ao lado deles (até 50 cm), então ficar ao lado da cama ou
    sentar nela não deixa o aviso ligado.

## Distância do aviso

0,4 m, 0,6 m (padrão) ou 0,9 m. A câmera começa a aparecer a 40% dessa distância. O aviso
sobe rápido (segurança) e some devagar (sem piscar).

## Testes e limites

**Testes:** o contorno, os laços, a cama, o corpo, as coisas que se mexem e os avisos têm
testes no JVM (`GuardianTest`).

**Limites:**
- Não testado num celular.
- A precisão depende do ARCore. Se ele perder o rastreio, o HyperGuardian avisa e não protege
  até voltar.
- O Gato vs. Vovó tem renderizador próprio e não passa pelo HyperGuardian.
- O HyperGuardian não substitui cuidado: jogue com espaço livre.
