import { PremiumDisplay } from '@/components/PremiumDisplay';
import { PremiumChart } from '@/components/PremiumChart';

export default function Home() {
  return (
    <div className="container mx-auto space-y-6 px-4 py-6">
      <PremiumDisplay />
      <PremiumChart />
    </div>
  );
}
