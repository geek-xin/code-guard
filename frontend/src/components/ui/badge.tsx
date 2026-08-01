import * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/utils';

const badgeVariants = cva(
  'inline-flex items-center gap-1 rounded border-chunky px-2 py-0.5 text-xs font-bold transition-colors',
  {
    variants: {
      variant: {
        default: 'border-ink bg-paper-alt text-ink',
        critical: 'border-ink bg-error text-white',
        high: 'border-ink bg-primary text-white',
        medium: 'border-ink bg-gold text-ink',
        low: 'border-ink bg-accent text-ink',
        info: 'border-ink bg-paper text-ink-muted',
        success: 'border-ink bg-secondary text-ink',
        warning: 'border-ink bg-gold text-ink',
        danger: 'border-ink bg-error text-white',
        outline: 'border-ink bg-white text-ink',
      },
    },
    defaultVariants: { variant: 'default' },
  },
);

export interface BadgeProps extends React.HTMLAttributes<HTMLDivElement>, VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, ...props }: BadgeProps) {
  return <div className={cn(badgeVariants({ variant }), className)} {...props} />;
}
export { Badge, badgeVariants };
