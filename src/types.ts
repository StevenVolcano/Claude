export interface TravelData {
  schemaVersion: 1
  /** USPS state abbreviation -> visited */
  states: Record<string, boolean>
  /** Interstate route (e.g. "I-80") -> USPS abbreviations of states traveled */
  interstates: Record<string, string[]>
  /** USPS state abbreviation -> capitol building visited */
  capitols: Record<string, boolean>
  /** Country key (ISO 3166-1 numeric as string, e.g. "250" for France) -> visited */
  countries: Record<string, boolean>
}

export interface Capitol {
  state: string
  stateName: string
  city: string
  building: string
  lat: number
  lng: number
}

/** Route -> states it passes through, from the data pipeline (drives the checklist) */
export type InterstateNetwork = Record<string, string[]>

export function emptyTravelData(): TravelData {
  return { schemaVersion: 1, states: {}, interstates: {}, capitols: {}, countries: {} }
}
