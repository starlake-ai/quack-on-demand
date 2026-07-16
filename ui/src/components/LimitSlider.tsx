/** Range slider + value label for one pod resource limit. The two presets pin the
 * bounds shared by the create-pool form (PoolSection) and the Nodes tab editor
 * (PoolDetailBody): 0.5-128 CPU cores in half-core steps, 1-1024 memory Gi. */
function LimitSlider({ min, max, step, unit, value, onChange }: {
  min: number;
  max: number;
  step: number;
  unit: string;
  value: number;
  onChange: (v: number) => void;
}) {
  return (
    <div className="row" style={{ gap: 8, alignItems: 'center' }}>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={e => onChange(Number(e.target.value))}
        style={{ width: 140 }}
      />
      <span className="subtle">{value} {unit}</span>
    </div>
  );
}

export function CpuLimitSlider({ value, onChange }: { value: number; onChange: (v: number) => void }) {
  return <LimitSlider min={0.5} max={128} step={0.5} unit="cores" value={value} onChange={onChange} />;
}

export function MemLimitSlider({ value, onChange }: { value: number; onChange: (v: number) => void }) {
  return <LimitSlider min={1} max={1024} step={1} unit="Gi" value={value} onChange={onChange} />;
}
