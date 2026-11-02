import { z } from 'zod';

const email = z.string().trim().toLowerCase().pipe(z.email());

export const signupSchema = z.strictObject({
  name: z.string().trim().min(1),
  email,
  password: z.string().min(12).max(20),
});

export const loginSchema = z.strictObject({
  email,
  password: z.string().min(1),
});

export type SignupInput = z.infer<typeof signupSchema>;
export type LoginInput = z.infer<typeof loginSchema>;
