import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";
import { format, getWeek, getYear, getDay } from "date-fns";

export function baseUrl(): string {
  return process.env.NEXT_PUBLIC_BASE_URL ?? "http://localhost:3000";
}

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function getGreeting(): string {
  const hour = new Date().getHours();
  if (hour < 12) return "Good morning";
  if (hour < 17) return "Good afternoon";
  return "Good evening";
}

export function toISODate(date: Date = new Date()): string {
  return format(date, "yyyy-MM-dd");
}

export function getDateParts(date: Date = new Date()) {
  return {
    dayOfWeek: getDay(date),
    weekNumber: getWeek(date),
    yearNumber: getYear(date),
  };
}
