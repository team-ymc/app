// 목업(x-import) 사용처에서 확인된 아이콘만 등록한다. 새 아이콘이 필요하면 여기에 추가.
import {
  Plus,
  CaretLeft,
  CaretRight,
  X,
  List,
  ClockCounterClockwise,
  NotePencil,
  SidebarSimple,
  PaperPlaneTilt,
  type Icon,
} from '@phosphor-icons/react';

export const ICONS: Record<string, Icon> = {
  plus: Plus,
  'caret-left': CaretLeft,
  'caret-right': CaretRight,
  x: X,
  list: List,
  'clock-counter-clockwise': ClockCounterClockwise,
  'note-pencil': NotePencil,
  'sidebar-simple': SidebarSimple,
  'paper-plane-tilt': PaperPlaneTilt,
};

export function iconComponent(kebab: string): Icon | undefined {
  return ICONS[kebab];
}
