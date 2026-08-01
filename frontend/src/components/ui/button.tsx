import * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/utils';

const buttonVariants = cva(
  'inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-md border-chunky text-sm font-bold transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/50 disabled:pointer-events-none disabled:opacity-50 active:translate-x-[2px] active:translate-y-[2px] active:shadow-none',
  {
    variants: {
      variant: {
        default: 'border-ink bg-primary text-white shadow-chunky-sm hover:bg-primary-hover',
        outline: 'border-ink bg-white text-ink shadow-chunky-sm hover:bg-paper-alt',
        ghost: 'border-transparent bg-transparent text-ink-muted hover:bg-black/5 hover:text-ink',
        danger: 'border-ink bg-error text-white shadow-chunky-sm hover:opacity-90',
        success: 'border-ink bg-success text-white shadow-chunky-sm hover:opacity-90',
        secondary: 'border-ink bg-paper-alt text-ink shadow-chunky-sm hover:bg-gold/60',
        plain: 'border-transparent bg-transparent text-primary hover:underline',
      },
      size: {
        default: 'h-9 px-4 py-2',
        sm: 'h-8 rounded-md px-3 text-xs',
        lg: 'h-11 rounded-md px-6 text-base',
        icon: 'h-9 w-9',
      },
    },
    defaultVariants: { variant: 'default', size: 'default' },
  },
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, ...props }, ref) => (
    <button className={cn(buttonVariants({ variant, size, className }))} ref={ref} {...props} />
  ),
);
Button.displayName = 'Button';

export { Button, buttonVariants };
