"""Offline editor: real captures, eased camera, cursor and clicks.

No application dependency. Python + Pillow and macOS's AVFoundation encoder only.
No model replies or attachment states are drawn. Compile the adjacent Swift file,
then run: python3 create-feature-demos.py --encoder /tmp/feature-encoder
"""
import argparse
import math
import subprocess
import tempfile
from functools import lru_cache
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
CAPTURES = ROOT / 'scripts' / 'feature-captures'
ASSETS = ROOT / 'public' / 'features'
W, H, FPS = 1224, 784, 24
PREVIEW_W, PREVIEW_H = 408, round(H / 3)
FULL = (612, 392, 1)
COMPOSER = (1040, 685, 3.35)

def shot(source, seconds, label, camera=FULL, pointer=None, click=False, hint='', fade=True):
    return dict(source=source, seconds=seconds, label=label, camera=camera,
                pointer=pointer, click=click, hint=hint, fade=fade)

def typing(prefix, label):
    return [shot(p.stem, .19, label, COMPOSER, hint='궁금한 내용을 입력하세요', fade=False)
            for p in sorted(CAPTURES.glob(f'{prefix}-type-*.jpg'))]

FILMS = {
    'prerequisite': [
        shot('prerequisite-off', 1.5, '읽다가 만난 낯선 개념', pointer=(460, 230), hint='읽던 흐름 그대로'),
        shot('prerequisite-off', 1.0, '선행지식 표시를 켜세요', (850, 140, 1.8), (936, 32), True),
        shot('prerequisite-on', .8, '필요한 개념이 본문에 표시됩니다', (850, 140, 1.8), (936, 32)),
        shot('prerequisite-on', 1.3, '궁금한 하이라이트를 클릭', (465, 320, 1.6), (410, 231)),
        shot('prerequisite-on', .45, '궁금한 하이라이트를 클릭', (465, 320, 1.6), (410, 231), True),
        shot('prerequisite-card', 1.0, '클릭 한 번, 개념이 선명하게', (410, 365, 2.05), (630, 480)),
        shot('prerequisite-card', 4.3, '영어와 한국어 설명을 그 자리에서', (410, 365, 2.05), hint='탭을 옮기지 않고 바로 이해하기'),
        shot('prerequisite-card', 1.2, '이제 논문을 이어서 읽으세요', FULL),
        shot('prerequisite-off', .6, '읽다가 만난 낯선 개념', FULL),
    ],
    'chat': [
        shot('chat-empty', 1.1, '논문 옆, 나만의 AI 튜터', FULL, (948, 718)),
        shot('chat-empty', 1.2, '질문은 평소 말하듯이', COMPOSER, (942, 743), True),
        *typing('chat', '논문에 대해 무엇이든 물어보세요'),
        shot('chat-final', 3.5, '궁금한 점을 하나씩 풀어가세요', COMPOSER, hint='읽고 → 묻고 → 이어서 질문하기'),
        shot('chat-final', 1.4, '읽기와 질문을 한 화면에서', FULL),
        shot('chat-empty', .6, '논문 옆, 나만의 AI 튜터', FULL),
    ],
    'inline-query': [
        shot('quote-before', 1.2, '01  인용 · 문장을 드래그', (470, 285, 1.55), (194, 254)),
        shot('quote-before', .85, '01  인용 · 문장을 드래그', (470, 285, 1.55), (809, 286)),
        shot('quote-selected', .8, '01  인용 · 질문하기를 클릭', (470, 285, 1.55), (475, 324), True),
        shot('quote-attached', 1.0, '01  인용 · 선택한 문장을 질문에 담기', COMPOSER, (940, 659)),
        *typing('quote', '01  인용 · 문맥을 담아 질문하세요'),
        shot('quote-final', 2.5, '01  인용 · 설명 없이도 정확한 문맥', COMPOSER, hint='선택한 문장이 인용으로 첨부됩니다'),
        shot('quote-final', .7, '01  인용', FULL),
        shot('image-before', 1.2, '02  이미지 · 궁금한 그림을 클릭', (487, 380, 1.45), (551, 391), True),
        shot('image-selected', .8, '02  이미지 · 질문하기를 클릭', (487, 300, 1.45), (630, 107), True),
        shot('image-attached', 1.0, '02  이미지 · 그림을 그대로 첨부', COMPOSER, (940, 659)),
        *typing('image', '02  이미지 · 그림을 보며 질문하세요'),
        shot('image-final', 2.5, '02  이미지 · 이미지를 담은 질문', COMPOSER, hint='복사하거나 업로드할 필요 없이'),
        shot('image-final', .7, '02  이미지', FULL),
        shot('table-before', 1.2, '03  표 · 비교할 표를 클릭', (454, 443, 1.7), (338, 416), True),
        shot('table-selected', .8, '03  표 · 질문하기를 클릭', (454, 443, 1.7), (769, 373), True),
        shot('table-attached', 1.0, '03  표 · 숫자와 구조를 함께 첨부', COMPOSER, (940, 659)),
        *typing('table', '03  표 · 표에 대해 질문하세요'),
        shot('table-final', 2.8, '03  표 · 성능과 비용을 함께 비교', COMPOSER, hint='표 전체가 질문의 문맥이 됩니다'),
        shot('table-final', .9, '문장도, 이미지도, 표도. 그대로 질문에.', FULL),
        shot('quote-before', .6, '01  인용 · 문장을 드래그', FULL),
    ],
    'translation': [
        shot('translation-off', 1.3, '전체 번역 · 버튼 하나로 시작', (850, 145, 1.7), (998, 32), True),
        shot('translation-below', 1.1, '원문 아래 · 문단의 흐름대로 읽기', FULL),
        shot('translation-below-read', 3.0, '원문 아래 · 문단의 흐름대로 읽기', (610, 440, 1.3), hint='원문과 한국어를 이어서'),
        shot('translation-below-read', .75, '번역 버튼으로 배치를 바꾸세요', FULL, (984, 32), True),
        shot('translation-side', 1.2, '원문 옆 · 두 언어를 나란히', FULL),
        shot('translation-side-read', 3.0, '원문 옆 · 표현을 비교하며 읽기', (611, 405, 1.3), hint='논문 전체에 적용되는 두 가지 배치'),
        shot('translation-inline-before', 1.1, '인라인 번역 · 필요한 문장만', (710, 286, 1.55), (504, 225)),
        shot('translation-inline-before', .7, '인라인 번역 · 문장을 드래그', (710, 286, 1.55), (928, 255)),
        shot('translation-inline-selected', .8, '인라인 번역 · 번역을 클릭', (650, 302, 1.7), (558, 293), True),
        shot('translation-inline-result', 1.0, '막히는 문장, 그 자리에서 이해하기', (596, 390, 2.25)),
        shot('translation-inline-result', 4.0, '막히는 문장, 그 자리에서 이해하기', (596, 390, 2.25), hint='읽던 자리를 떠나지 않아도 됩니다'),
        shot('translation-inline-result', 1.2, '한 문장부터 논문 전체까지', FULL),
        shot('translation-off', .6, '전체 번역 · 버튼 하나로 시작', FULL),
    ],
    'bookmap': [
        shot('bookmap-closed', 1.0, '한 권의 논문, 전체 구조를 펼치다', FULL, (607, 467)),
        shot('bookmap-closed', 1.0, '섹션 버튼으로 구조를 펼치세요', (608, 411, 1.75), (607, 467), True),
        shot('bookmap-root-open', 1.2, '전체 흐름이 한눈에', FULL),
        shot('bookmap-root-open', 1.0, '하위 섹션도 차근차근', (510, 610, 1.6), (510, 677), True),
        shot('bookmap-architecture-open', 1.0, '하위 섹션도 차근차근', FULL),
        shot('bookmap-architecture-open', .9, '전체 펼치기로 큰 그림을 보세요', (1055, 598, 1.75), (1092, 666), True),
        shot('bookmap-expand-all', .6, '화면에 맞춰 전체 구조 확인', (1055, 598, 1.75), (1133, 666), True),
        shot('bookmap-fit-all', 2.8, '논문의 전체 흐름을 한눈에', FULL, hint='섹션 사이의 관계까지 선명하게'),
        shot('bookmap-fit-all', 1.1, '읽고 싶은 섹션을 클릭', (500, 397, 1.6), (517, 356), True),
        shot('bookmap-section', 1.3, '지도에서 바로 본문으로', (990, 300, 1.7), (1083, 187)),
        shot('bookmap-section', 1.1, '다음 섹션으로 자연스럽게', (990, 300, 1.7), (1083, 187), True),
        shot('bookmap-next', 1.0, '구조를 따라 읽는 새로운 방법', (990, 300, 1.7), (1083, 187), True),
        shot('bookmap-subsection', 3.2, '섹션별로 읽고, 번역도 함께', (994, 388, 1.7), hint='이전 · 다음 · 상위 섹션으로 이동'),
        shot('bookmap-subsection', 1.1, '구조를 따라 읽는 새로운 방법', FULL),
        shot('bookmap-fit-all', 1.2, '한 권의 논문, 전체 구조를 펼치다', FULL),
        shot('bookmap-closed', .6, '한 권의 논문, 전체 구조를 펼치다', FULL),
    ],
}

@lru_cache(maxsize=100)
def source(name):
    im = Image.open(CAPTURES / f'{name}.jpg').convert('RGB')
    frame = Image.new('RGB', (W, H), '#f7f1e7')
    frame.paste(im, (0, (H - im.height) // 2))
    return frame, (H - im.height) // 2

def ease(t):
    return t * t * (3 - 2 * t)

def mix(a, b, t):
    return tuple(x + (y - x) * t for x, y in zip(a, b))

def crop_box(camera):
    cx, cy, scale = camera
    cw, ch = W / scale, H / scale
    left = max(0, min(W - cw, cx - cw / 2))
    top = max(0, min(H - ch, cy - ch / 2))
    return (left, top, left + cw, top + ch)

def render(item, previous, p):
    camera = mix(previous['camera'], item['camera'], ease(min(1, p / .78)))
    box = crop_box(camera)
    im, offset = source(item['source'])
    if item['fade'] and previous['source'] != item['source'] and p < .18:
        im = Image.blend(source(previous['source'])[0], im, ease(p / .18))
    frame = im.resize((W, H), Image.Resampling.BICUBIC, box=box)
    draw = ImageDraw.Draw(frame)
    if item['pointer']:
        start = previous['pointer'] or item['pointer']
        px, py = mix(start, item['pointer'], ease(min(1, p / .72)))
        py += offset
        scale = W / (box[2] - box[0])
        x, y = (px - box[0]) * scale, (py - box[1]) * scale
        if item['click'] and p > .73:
            radius = 10 + (p - .73) / .27 * 24
            draw.ellipse((x-radius, y-radius, x+radius, y+radius), outline='#aa8551', width=3)
        points = [(x,y), (x+2,y+29), (x+9,y+22), (x+16,y+34),
                  (x+22,y+30), (x+15,y+18), (x+26,y+17)]
        draw.polygon([(a+2,b+2) for a,b in points], fill='#6e6250')
        draw.polygon(points, fill='#243a55')
        draw.line(points+[points[0]], fill='white', width=2, joint='curve')
    return frame

def build(name, encoder, previews):
    timeline = FILMS[name]
    poster_index = next(i for i,s in enumerate(timeline) if s['source'].endswith(('card','final','result','fit-all')))
    with tempfile.TemporaryDirectory(prefix='paperteacher-film-') as temp:
        output = Path(temp) / f'{name}.mp4'
        process = subprocess.Popen([encoder, str(output), str(W), str(H), str(FPS)], stdin=subprocess.PIPE)
        previous = {**timeline[0], 'camera': FULL}
        samples = []
        for index, item in enumerate(timeline):
            count = round(item['seconds'] * FPS)
            for tick in range(count):
                frame = render(item, previous, tick / max(1, count-1))
                process.stdin.write(frame.convert('RGBA').tobytes('raw', 'BGRA'))
                if tick == count-1 and item['seconds'] > .7:
                    samples.append(frame.resize((PREVIEW_W, PREVIEW_H), Image.Resampling.LANCZOS))
                if index == poster_index and tick == count-1:
                    frame.save(ASSETS / f'{name}-poster.jpg', quality=90)
            previous = item
        process.stdin.close()
        if process.wait() != 0:
            raise RuntimeError(f'{name} encoding failed')
        output.replace(ASSETS / output.name)
        sheet = Image.new('RGB', (PREVIEW_W*3, PREVIEW_H*math.ceil(len(samples)/3)), '#f7f1e7')
        for i, sample in enumerate(samples):
            sheet.paste(sample, ((i%3)*PREVIEW_W, (i//3)*PREVIEW_H))
        sheet.save(previews / f'{name}-storyboard.jpg', quality=88)
        print(f'{name}: {(ASSETS / output.name).stat().st_size / 1024 / 1024:.2f} MB', flush=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--encoder', required=True)
    parser.add_argument('--only', choices=list(FILMS))
    parser.add_argument('--previews', type=Path, default=Path('/tmp/paperteacher-storyboards'))
    args = parser.parse_args()
    args.previews.mkdir(parents=True, exist_ok=True)
    for film in ([args.only] if args.only else FILMS):
        build(film, args.encoder, args.previews)
