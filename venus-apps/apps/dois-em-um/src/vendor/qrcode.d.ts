declare const qrcode: { (tipo: number, nivel: string): { addData(t: string): void; make(): void; getModuleCount(): number; isDark(y: number, x: number): boolean }; stringToBytes: unknown; stringToBytesFuncs: Record<string, unknown> };
export default qrcode;
