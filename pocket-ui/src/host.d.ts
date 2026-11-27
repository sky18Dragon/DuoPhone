// Only symbols inspected in pinned PocketJS install_host() plus DuoPhone's own adapter.
declare const ui: {
  createNode(type: number): number;
  setProp(id: number, property: number, value: number): void;
  insertBefore(parent: number, child: number, anchor: number): void;
  setText(id: number, text: string): void;
  hitTestBounds(x: number, y: number): number;
};
declare function __duoDispatch(json: string): void;
declare var duoReceive: (json: string) => void;
declare var frame: (keys: number, analog: number, contacts: number[], hits: number[]) => void;
